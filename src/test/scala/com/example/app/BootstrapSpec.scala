package com.example.app

import com.github.jeffgarratt.hl.fabric.sdk.Bootstrap.ChannelId
import com.github.jeffgarratt.hl.fabric.sdk._
import com.google.protobuf.ByteString
import main.app.{AppDescriptor, AppDescriptors, Query}
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.bouncycastle.util.encoders.Hex
import org.hyperledger.fabric.protos.peer.chaincode.ChaincodeSpec
import org.hyperledger.fabric.protos.peer.proposal_response.ProposalResponse
import org.scalatest.{AppendedClues, FunSpec, GivenWhenThen}

import scala.collection.concurrent.TrieMap
import scala.concurrent.Await
import scala.concurrent.duration._

class BootstrapSpec(projectName: String) extends FunSpec with GivenWhenThen with AppendedClues {

  val ctx = BootstrapSpec.getContext(projectName)

  val dev0Org0 = ctx.getDirectory.get.users.find(_.name == "dev0Org0").get
  val nodeAdminTuple = Directory.natsForUser(ctx.getDirectory.get, dev0Org0)(0)
  val cchExample02 = new ChaincodeHelper(ctx, dev0Org0, nodeAdminTuple, endorsers = List("peer0"))
  // partial func helpers for building chaincode specs and invocation specs
  val defaultChannelName = "com.peerorg0.blockchain.channel.medical"
  val getChaincodeSpec = Endorser.getChaincodeSpec(chaincodeType = ChaincodeSpec.Type.GOLANG, path = "github.com/hyperledger/fabric/examples/chaincode/go/marketplace/app_mgr", name = "appmgr", _: List[ByteString], version = "1.0")
  val getInvocationSpec = Endorser.InvocationSpec(_: ChaincodeSpec, channelName = Some(defaultChannelName), proposalResponseHandler = Some(Endorser.getHandler(AppDescriptors)))

  val deliverSpecOrderer0 = Deliver.DeliverSpec(dev0Org0, nodeAdminTuple, defaultChannelName, "orderer0", port = 7050, seekInfo = Deliver.seekInfoAllAndWait, timeout = 10 minutes)
  val deliverSpecPeer0 = Deliver.DeliverSpec(dev0Org0, nodeAdminTuple, defaultChannelName, "peer0", port = 7051, seekInfo = Deliver.seekInfoAllAndWait, timeout = 10 minutes)
  val broadcastSpecOrderer0 = Orderer.BroadcastSpec(nodeName = "orderer0", timeout = 10 minutes)

  def getMedicalChannelForOrg(medicalOrg : Organization) = {
    s"com.${medicalOrg.name.toLowerCase()}.blockchain.channel.medical"
  }

  val peerOrg0 = ctx.getDirectory.get.orgs.find(_.name == "peerOrg0").get
  val peerOrg1 = ctx.getDirectory.get.orgs.find(_.name == "peerOrg1").get
  val peerOrg2 = ctx.getDirectory.get.orgs.find(_.name == "peerOrg2").get

  val dev0Org1 = ctx.getDirectory.get.users.find(_.name == "dev0Org1").get
  val natDev0Org1 = Directory.natsForUser(ctx.getDirectory.get, dev0Org1)(0)
  val dev0Org2 = ctx.getDirectory.get.users.find(_.name == "dev0Org2").get
  val natDev0Org2 = Directory.natsForUser(ctx.getDirectory.get, dev0Org2)(0)

  val dev0Org7 = ctx.getDirectory.get.users.find(_.name == "dev0Org7").get
  val natDev0Org7 = Directory.natsForUser(ctx.getDirectory.get, dev0Org7)(0)
  val cc = cchExample02.copy(user = dev0Org7, node_admin_tuple = natDev0Org7, endorsers = List("peer0"))
  val queryPeer0 = getQuery(Query(), cc.copy(endorsers = List("peer0")), Some(getMedicalChannelForOrg(peerOrg0)))
  val queryPeer1 = getQuery(Query(), cc.copy(endorsers = List("peer1")), Some(getMedicalChannelForOrg(peerOrg1)))
  val queryPeer2 = getQuery(Query(), cc.copy(endorsers = List("peer2")), Some(getMedicalChannelForOrg(peerOrg2)))
  val queryAllMedical = Task.gatherUnordered(List(queryPeer0,queryPeer1,queryPeer2))

  val queryPeer7 = getQuery(Query(), cc.copy(endorsers = List("peer7")), Some("com.peerorg7.blockchain.channel.worker"))

  // Query for a value, use a factory to create query tasks
  def getQuery(query: Query, chaincodeHelper: ChaincodeHelper, channelName :Option[String] = Some(defaultChannelName)) = {
    val getInvocationSpec = Endorser.InvocationSpec(_: ChaincodeSpec, channelName = channelName, proposalResponseHandler = Some(Endorser.getHandler(AppDescriptors)))
    Task.eval({
      val irSet = Await.result(ChaincodeHelper.getTask(chaincodeHelper.send(getInvocationSpec(getChaincodeSpec(List(ByteString.copyFromUtf8("getAppDescriptors"), query.toByteString))))).runToFuture, 1.seconds)
      irSet.map(ir => (ir.interaction.endorser, ir.extractedResponse.getOrElse(AppDescriptors(descriptors = Map("UNEXPECTED RESPONSE" -> AppDescriptor(description = ir.interaction.getProposalResponse.toString)))))).toMap
    })
  }

  val createRequestIdTask = Task.eval {
      s"REQ-${new String(Hex.encode(Bootstrap.getNonce.toByteArray))}"
  }

  def getTask[A](ccFunc: => Either[String, List[Either[String, Endorser.Interaction[A]]]], timeout: Duration = 1 seconds) = {
    val task = Task.eval({
      val resultOfSend = ccFunc
      val interactions = resultOfSend.right.get.map(_.right.get)
      val r = Task.gather(interactions.map(i => Task.fromFuture(i.proposalResponseFuture))).runToFuture
      Await.result(r, timeout)
      interactions
    })
    task
  }

  // Invoker tasks
  def getCreateAppDescriptor(chaincodeHelper: ChaincodeHelper, user: User, channelName : ChannelId, appDescriptorKey : String, appDescriptor: AppDescriptor) = {
    val gis = Endorser.InvocationSpec(_: ChaincodeSpec, channelName = Some(channelName), proposalResponseHandler = Some(Endorser.getHandler(AppDescriptor)))
    Task.eval({
      val taskInvoker = getTask(chaincodeHelper.send(gis(getChaincodeSpec(List(ByteString.copyFromUtf8("createAppDescriptor"), ByteString.copyFromUtf8(appDescriptorKey), appDescriptor.toByteString)))))
      val interactions = Await.result(taskInvoker.runToFuture, 1 seconds)
      val irSet = interactions.map(_.getResult()).map(_.right.get)
      val signedTx = Bootstrap.createSignedTransaction(user, irSet)
      val results = (signedTx, irSet)
      results
//      val signedTx = Bootstrap.createSignedTransaction(user, irSet).right.get
//      signedTx
    })
  }

  def getCreateRecordInteraction(nat : NodeAdminTuple, targetPeer : String, channelId: ChannelId, key : String, value : AppDescriptor)  = {
    Task.eval({
      val result = Await.result(getCreateAppDescriptor(cchExample02.copy(user = nat.user, node_admin_tuple = nat, endorsers = List(targetPeer)), nat.user, channelId, key, value).runToFuture, 1.seconds)
      result._1 match {
        case Right(signedTx) => {
          val interaction = ctx.Interaction(Task.eval(signedTx), broadcastSpecOrderer0, deliverSpecPeer0.copy(nodeName = targetPeer, signer = nat.user, nodeAdminTuple = nat, channelId = channelId))
          Right(interaction, result._2)
        }
        case Left(msg) => Left(msg, result._2)
      }
    })
  }

  // Invoker tasks
  def getInvoker(chaincodeHelper: ChaincodeHelper, user: User, arg1: String = "a", arg2: String = "b", arg3: String = "10") = {
    Task.eval({
      val taskInvoker = ChaincodeHelper.getTask(cchExample02.send(getInvocationSpec(getChaincodeSpec(List("invoke", arg1, arg2, arg3).map(ByteString.copyFromUtf8)))))
      val irSet = Await.result(taskInvoker.runToFuture, 1 seconds)
      val signedTx = Bootstrap.createSignedTransaction(user, irSet).right.get
      signedTx
    })
  }

  def retryWithDelay[A](t: Task[A], delay: FiniteDuration, restarts: Int) =
    t.onErrorFallbackTo(t.delayExecution(delay).onErrorRestart(restarts))

  describe("Query and deliver on a sample blockchain") {

    it("should support basic querying") {

      Given(s"I have a user ${dev0Org0.name}")

      When("user queries for 'a'")
      val queryAResult = retryWithDelay(queryPeer0,.2 seconds, 5).runToFuture
      Await.result(queryAResult, 3 seconds)

      Then("the result should be success")
      assert(queryAResult.value.get.isSuccess) withClue (queryAResult.value.get)

      And("the query should return 2 values")
      assert(queryAResult.value.get.get.size == 2)
    }

    it("should support deliver on orderer0") {

      Given(s"I have a user ${dev0Org0.name}")

      When(s"user ${dev0Org0.name} requests deliver")
      val deliveryFromOrderer0 = ctx.getDeliveryFromOrderer(deliverSpecOrderer0)

      Then("the last block should be available")
      val observable = deliveryFromOrderer0.getEnvelopeWrappers(10)
      Await.result(retryWithDelay(observable.lastL,.2 seconds, 5).runToFuture, 1 seconds)
    }

    it("should support deliver on peer0") {

      Given(s"I have a user ${dev0Org0.name}")

      When(s"user ${dev0Org0.name} requests deliver on Peer0")
      val delivery = ctx.getDeliveryFromPeer(deliverSpecPeer0)

      Then("the last block should be available")
      val observable = delivery.getEnvelopeWrappers(10)
      Await.result(retryWithDelay(observable.lastL,.2 seconds, 5).runToFuture, 1 seconds)
    }


  }

  describe("Invocation on sample blockchain") {

    it("should support non-memoized interaction") {

      Given(s"I have a user ${dev0Org0.name}")

      When("user invokes the transfer operation from a to b of value 10")

      val fullTxTask = ctx.getFullTxTask(getInvoker(cchExample02, dev0Org0,"a", "b", "10"), broadcastSpecOrderer0, deliverSpecPeer0)
      val result = fullTxTask.runToFuture
      Await.result(result, 3 seconds)

      Then("the result should be success")
      assert(result.value.get.isSuccess) withClue (result.value.get)

      And("the committed transaction should be valid")
      val envWrapper = result.value.get.get._3
      assert(envWrapper.validationCode.get.isValid) withClue (envWrapper.validationCode.get)

    }

    it("should support memoized interaction") {

      Given(s"I have a user ${dev0Org0.name}")

      When("user invokes the transfer operation from b to a of value 10")
      val requestId = Await.result(createRequestIdTask.runToFuture, 1.seconds)
      val createAppDescriptorTask = getCreateAppDescriptor(cchExample02, dev0Org0, getMedicalChannelForOrg(peerOrg0), requestId, AppDescriptor(description = "105"))
      val interaction = ctx.Interaction(getInvoker(cchExample02, dev0Org0,"b", "a", "10"), broadcastSpecOrderer0, deliverSpecPeer0)
      val result = interaction.fullTxTask.runToFuture
      Await.result(result, 3 seconds)

      Then("the result should be success")
      assert(result.value.get.isSuccess) withClue (result.value.get)

      And("the committed transaction should be valid")
      val envWrapper = result.value.get.get._3
      assert(envWrapper.validationCode.get.isValid) withClue (envWrapper.validationCode.get)

    }

  }

  describe("CSSC System chaincode interaction") {

    it("should support invocation of channel list from peer0 and peer1 for user dev0Org0") {
      Given(s"I have a user ${dev0Org0.name}")
      val csscHelper = new CsccHelper(ctx, dev0Org0, nodeAdminTuple, endorsers = List("peer0", "peer1"))

      When("user invokes the channel list operation")
      val irSet = Await.result(ChaincodeHelper.getTask(csscHelper.getChannelList).runToFuture, 1.seconds)
      val results = irSet.map(ir => (ir.interaction.endorser, ir.extractedResponse.get)).toMap

      Then(s"peer0 should contain the channel -> '$defaultChannelName'")
      assert(results("peer0").channels.find(_.channelId == defaultChannelName).size > 0) withClue (results("peer0").channels.size)

      And(s"peer1 should contain the channel -> '$defaultChannelName'")
      assert(results("peer1").channels.find(_.channelId == defaultChannelName).size > 0) withClue (results("peer1").channels.size)
    }
  }


}


object BootstrapSpec {

  private val contextMap = TrieMap.empty[String, Context]

  def getContext(projectName: String) = {
    contextMap.getOrElseUpdate(projectName, {
      new LocalDockerContext(projectName = projectName, rootPath = "/opt/gopath/src/github.com/hyperledger/fabric/fabric-explorer".split("/") ++ Seq("tmp"))
    })
  }

}
