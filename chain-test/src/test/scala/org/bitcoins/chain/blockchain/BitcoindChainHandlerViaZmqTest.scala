package org.bitcoins.chain.blockchain

import org.bitcoins.rpc.util.RpcUtil
import org.bitcoins.testkit.chain.ChainDbUnitTest
import org.bitcoins.testkit.chain.fixture.BitcoindChainHandlerViaZmq
import org.scalatest.FutureOutcome

class BitcoindChainHandlerViaZmqTest extends ChainDbUnitTest {

  override type FixtureParam = BitcoindChainHandlerViaZmq

  override def withFixture(test: OneArgAsyncTest): FutureOutcome =
    withBitcoindChainHandlerViaZmq(test)

  behavior of "BitcoindChainHandlerViaZmq"

  it must "peer with bitcoind via zmq and have blockchain info relayed" in {
    bitcoindChainHandler: BitcoindChainHandlerViaZmq =>
      val bitcoind = bitcoindChainHandler.bitcoindRpc

      val chainHandler = bitcoindChainHandler.chainHandler

      for {
        _ <-
          chainHandler
            .getBlockCount()
            .map(count => assert(count == 0))
        address <- bitcoind.getNewAddress
        hash +: _ <- bitcoind.generateToAddress(1, address)
        _ <- {
          //test case is totally async since we
          //can't monitor processing flow for zmq
          //so we just need to await until we
          //have fully processed the header
          RpcUtil.awaitConditionF(() =>
            chainHandler.getHeader(hash).map(_.isDefined))
        }

        header <- chainHandler.getHeader(hash)
      } yield assert(header.get.hashBE == hash)
  }

}
