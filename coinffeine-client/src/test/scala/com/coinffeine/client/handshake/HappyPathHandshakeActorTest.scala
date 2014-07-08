package com.coinffeine.client.handshake

import scala.concurrent.duration._

import com.coinffeine.client.handshake.HandshakeActor.HandshakeSuccess
import com.coinffeine.common.PeerConnection
import com.coinffeine.common.bitcoin.{Hash, ImmutableTransaction, TransactionSignature}
import com.coinffeine.common.bitcoin.Implicits._
import com.coinffeine.common.blockchain.BlockchainActor._
import com.coinffeine.common.exchange.{Both, Exchange, MockExchangeProtocol}
import com.coinffeine.common.protocol._
import com.coinffeine.common.protocol.gateway.MessageGateway.{ReceiveMessage, Subscribe}
import com.coinffeine.common.protocol.messages.arbitration.CommitmentNotification
import com.coinffeine.common.protocol.messages.handshake._

class HappyPathHandshakeActorTest extends HandshakeActorTest("happy-path") {

  override def protocolConstants = ProtocolConstants(
    commitmentConfirmations = 1,
    resubmitRefundSignatureTimeout = 1 minute,
    refundSignatureAbortTimeout = 1 minute
  )

  "Handshake happy path" should "subscribe to the relevant messages when initialized" in {
    gateway.expectNoMsg()
    givenActorIsInitialized()
    val Subscribe(filter) = gateway.expectMsgClass(classOf[Subscribe])
    val otherId = Exchange.Id("other-id")
    val relevantPeerHandshake =
      PeerHandshake(exchange.id, handshake.exchange.counterpart.bitcoinKey.publicKey, "foo")
    val relevantSignatureRequest =
      RefundSignatureRequest(exchange.id, ImmutableTransaction(handshake.counterpartRefund))
    val irrelevantSignatureRequest =
      RefundSignatureRequest(otherId, ImmutableTransaction(handshake.counterpartRefund))
    filter(fromCounterpart(relevantPeerHandshake)) should be (true)
    filter(fromBroker(relevantPeerHandshake)) should be (false)
    filter(fromCounterpart(relevantSignatureRequest)) should be (true)
    filter(ReceiveMessage(relevantSignatureRequest, PeerConnection("other"))) should be (false)
    filter(fromCounterpart(irrelevantSignatureRequest)) should be (false)
    filter(fromCounterpart(
      RefundSignatureResponse(exchange.id, MockExchangeProtocol.RefundSignature))) should be (true)
    filter(fromBroker(CommitmentNotification(exchange.id, Both(mock[Hash], mock[Hash])))) should be (true)
    filter(fromBroker(ExchangeAborted(exchange.id, "failed"))) should be (true)
    filter(fromCounterpart(ExchangeAborted(exchange.id, "failed"))) should be (false)
    filter(fromBroker(ExchangeAborted(otherId, "failed"))) should be (false)
  }

  it should "send peer handshake" in {
    shouldForwardPeerHandshake()
  }

  it should "request refund transaction signature after getting counterpart peer handshake" in {
    givenCounterpartPeerHandshake()
    shouldForwardRefundSignatureRequest()
  }

  it should "reject signature of invalid counterpart refund transactions" in {
    val invalidRequest =
      RefundSignatureRequest(exchange.id, ImmutableTransaction(handshake.invalidRefundTransaction))
    gateway.send(actor, fromCounterpart(invalidRequest))
    gateway.expectNoMsg(100 millis)
  }

  it should "sign counterpart refund while waiting for our refund" in {
    shouldSignCounterpartRefund()
  }

  it should "don't be fooled by invalid refund TX or source and resubmit signature request" in {
    gateway.send(
      actor, fromCounterpart(RefundSignatureResponse(exchange.id, mock[TransactionSignature])))
    shouldForwardRefundSignatureRequest()
  }

  it should "send commitment TX to the broker after getting his refund TX signed" in {
    givenValidRefundSignatureResponse()
    shouldForward (ExchangeCommitment(exchange.id, handshake.myDeposit)) to broker
  }

  it should "sign counterpart refund after having our refund signed" in {
    shouldSignCounterpartRefund()
  }

  it should "wait until the broker publishes commitments" in {
    listener.expectNoMsg(100 millis)
    gateway.send(actor, fromBroker(CommitmentNotification(
      exchange.id,
      Both(
        handshake.myDeposit.get.getHash,
        handshake.counterpartCommitmentTransaction.getHash
      )
    )))
    val confirmations = protocolConstants.commitmentConfirmations
    blockchain.expectMsgAllOf(
      WatchTransactionConfirmation(handshake.myDeposit.get.getHash, confirmations),
      WatchTransactionConfirmation(handshake.counterpartCommitmentTransaction.getHash, confirmations)
    )
  }

  it should "wait until commitments are confirmed" in {
    listener.expectNoMsg(100 millis)
    for (tx <- Seq(
      handshake.myDeposit.get.getHash,
      handshake.counterpartCommitmentTransaction.getHash
    )) {
      blockchain.send(actor, TransactionConfirmed(tx, 1))
    }
    val result = listener.expectMsgClass(classOf[HandshakeSuccess])
    result.refundTransaction should be (handshake.mySignedRefund)
    result.bothCommitments.buyer should be (handshake.myDeposit.get.getHash)
    result.bothCommitments.seller should be (handshake.counterpartCommitmentTransaction.getHash)
  }

  it should "finally terminate himself" in {
    listener.expectTerminated(actor)
  }
}
