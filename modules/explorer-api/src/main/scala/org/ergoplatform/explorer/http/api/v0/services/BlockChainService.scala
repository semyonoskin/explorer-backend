package org.ergoplatform.explorer.http.api.v0.services

import cats.effect.Sync
import cats.instances.option._
import cats.instances.list._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.list._
import cats.syntax.traverse._
import cats.Monad
import fs2.Stream
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import mouse.anyf._
import org.ergoplatform.ErgoLikeContext.Height
import org.ergoplatform.explorer.Err.RequestProcessingErr.InconsistentDbData
import org.ergoplatform.explorer.db.Trans
import org.ergoplatform.explorer.db.algebra.LiftConnectionIO
import org.ergoplatform.explorer.db.models.Transaction
import org.ergoplatform.explorer.db.repositories._
import org.ergoplatform.explorer.http.api.models.{Items, Paging, Sorting}
import org.ergoplatform.explorer.http.api.v0.models.{BlockInfo, BlockReferencesInfo, BlockSummary, FullBlockInfo}
import org.ergoplatform.explorer.syntax.stream._
import org.ergoplatform.explorer.{CRaise, BlockId}
import tofu.syntax.raise._

/** A service providing an access to the blockchain data.
  */
trait BlockChainService[F[_]] {

  /** Get height of the best block.
    */
  def getBestHeight: F[Int]

  /** Get summary for a block with a given `id`.
    */
  def getBlockSummaryById(id: BlockId): F[Option[BlockSummary]]

  /** Get a slice of block info items.
    */
  def getBlocks(paging: Paging, sorting: Sorting): F[Items[BlockInfo]]

  /** Get all blocks with id matching a given `query`.
    */
  def getBlocksByIdLike(query: String): F[List[BlockInfo]]

  /** Get ids of all blocks at the given `height`.
    */
  def getBlockIdsAtHeight(height: Int): F[List[BlockId]]
}

object BlockChainService {

  def apply[
    F[_]: Sync,
    D[_]: LiftConnectionIO: CRaise[*[_], InconsistentDbData]: Monad
  ](xa: D Trans F): F[BlockChainService[F]] =
    Slf4jLogger
      .create[F]
      .flatMap { implicit logger =>
        (
          HeaderRepo[F, D],
          BlockInfoRepo[F, D],
          TransactionRepo[F, D],
          BlockExtensionRepo[F, D],
          AdProofRepo[F, D],
          InputRepo[F, D],
          DataInputRepo[F, D],
          OutputRepo[F, D],
          AssetRepo[F, D]
        ).mapN(new Live(_, _, _, _, _, _, _, _, _)(xa))
      }

  final private class Live[
    F[_]: Sync: Logger,
    D[_]: CRaise[*[_], InconsistentDbData]: Monad
  ](
    headerRepo: HeaderRepo[D],
    blockInfoRepo: BlockInfoRepo[D],
    transactionRepo: TransactionRepo[D, Stream],
    blockExtensionRepo: BlockExtensionRepo[D],
    adProofRepo: AdProofRepo[D],
    inputRepo: InputRepo[D],
    dataInputRepo: DataInputRepo[D],
    outputRepo: OutputRepo[D, Stream],
    assetRepo: AssetRepo[D, Stream]
  )(trans: D Trans F)
    extends BlockChainService[F] {

    def getBestHeight: F[Int] =
      (headerRepo.getBestHeight ||> trans.xa)
        .flatTap(h => Logger[F].trace(s"Reading best height from db: $h"))

    def getBlockSummaryById(id: BlockId): F[Option[BlockSummary]] = {
      val summary =
        for {
          blockInfoOpt <- getFullBlockInfo(id)
          ancestorOpt <- blockInfoOpt
                           .flatTraverse(h => headerRepo.getByParentId(h.header.id))
                           .asStream
        } yield blockInfoOpt.map { blockInfo =>
          val refs =
            BlockReferencesInfo(blockInfo.header.parentId, ancestorOpt.map(_.id))
          BlockSummary(blockInfo, refs)
        }

      (summary ||> trans.xas).compile.last.map(_.flatten)
    }

    def getBlocks(paging: Paging, sorting: Sorting): F[Items[BlockInfo]] =
      headerRepo.getBestHeight.flatMap { total =>
        blockInfoRepo
          .getMany(paging.offset, paging.limit, sorting.order.value, sorting.sortBy)
          .map(_.map(BlockInfo(_)))
          .map(Items(_, total))
      } ||> trans.xa

    def getBlocksByIdLike(query: String): F[List[BlockInfo]] =
      blockInfoRepo
        .getManyByIdLike(query)
        .map(_.map(BlockInfo(_))) ||> trans.xa

    def getBlockIdsAtHeight(height: Height): F[List[BlockId]] =
      headerRepo
        .getAllByHeight(height)
        .map(_.map(_.id)) ||> trans.xa

    private def getFullBlockInfo(id: BlockId): Stream[D, Option[FullBlockInfo]] =
      for {
        header       <- headerRepo.get(id).asStream.unNone
        txs          <- transactionRepo.getAllByBlockId(id).fold(Array.empty[Transaction])(_ :+ _).map(_.toList)
        blockSizeOpt <- blockInfoRepo.getBlockSize(id).asStream
        bestHeight   <- headerRepo.getBestHeight.asStream
        txIdsNel     <- txs.map(_.id).toNel.orRaise[D](InconsistentDbData("Empty txs")).asStream
        inputs       <- inputRepo.getAllByTxIds(txIdsNel).asStream
        dataInputs   <- dataInputRepo.getAllByTxIds(txIdsNel).asStream
        outputs      <- outputRepo.getAllByTxIds(txIdsNel).asStream
        boxIdsNel    <- outputs.map(_.output.boxId).toNel.orRaise[D](InconsistentDbData("Empty outputs")).asStream
        assets       <- assetRepo.getAllByBoxIds(boxIdsNel).asStream
        adProofsOpt  <- adProofRepo.getByHeaderId(id).asStream
        extensionOpt <- blockExtensionRepo.getByHeaderId(id).asStream
      } yield (blockSizeOpt, extensionOpt)
        .mapN { (size, ext) =>
          val numConfirmations = bestHeight - header.height + 1
          FullBlockInfo(
            header,
            txs,
            numConfirmations,
            inputs,
            dataInputs,
            outputs,
            assets,
            ext,
            adProofsOpt,
            size
          )
        }
  }
}
