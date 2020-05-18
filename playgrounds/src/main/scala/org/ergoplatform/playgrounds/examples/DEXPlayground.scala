package org.ergoplatform.playgrounds.examples

object DEXPlayground {
  import org.ergoplatform.compiler.ErgoScalaCompiler._
  import org.ergoplatform.playground._

  def buyerContract(
    buyerParty: Party,
    token: TokenInfo,
    tokenPrice: Long,
    dexFeePerToken: Long
  ) = {

    val buyerPk = buyerParty.wallet.getAddress.pubKey

    val buyerContractEnv: ScriptEnv =
      Map("buyerPk" -> buyerPk, "tokenId" -> token.tokenId)

    val buyerScript = s"""buyerPk || {

      val tokenPrice = $tokenPrice
      val dexFeePerToken = $dexFeePerToken

      val spendingSellOrders = INPUTS.filter { (b: Box) => 
        b.R4[Coll[Byte]].isDefined && b.R5[Long].isDefined && {
          val sellOrderTokenId = b.R4[Coll[Byte]].get
          sellOrderTokenId == tokenId && {
            b.tokens.size == 1 && b.tokens(0)._1 == tokenId
          }
        }
      }

      val returnBoxes = OUTPUTS.filter { (b: Box) => 
        b.R4[Coll[Byte]].isDefined && b.R4[Coll[Byte]].get == SELF.id && b.propositionBytes == buyerPk.propBytes
      }

      val boxesAreSortedByTokenPrice = { (boxes: Coll[Box]) => 
        boxes.fold((0L, true), { (t: (Long, Boolean), box: Box) => 
          val prevBoxTokenPrice = t._1
          val isSorted = t._2
          val boxTokenPrice = box.R5[Long].getOrElse(0L)
          (boxTokenPrice, isSorted && boxTokenPrice >= prevBoxTokenPrice)
        })._2
      }

      returnBoxes.size == 1 && spendingSellOrders.size > 0 && boxesAreSortedByTokenPrice(spendingSellOrders) && {
        val returnBox = returnBoxes(0)
        val returnTokenAmount = if (returnBox.tokens.size == 1) returnBox.tokens(0)._2 else 0L
        
        val expectedDexFee = dexFeePerToken * returnTokenAmount
        
        val foundNewOrderBoxes = OUTPUTS.filter { (b: Box) => 
          val tokenIdParameterIsCorrect = b.R4[Coll[Byte]].isDefined && b.R4[Coll[Byte]].get == tokenId 
          val tokenPriceParameterIsCorrect = b.R5[Long].isDefined && b.R5[Long].get == tokenPrice
          val dexFeePerTokenParameterIsCorrect = b.R6[Long].isDefined && b.R6[Long].get == dexFeePerToken
          val contractParametersAreCorrect = tokenIdParameterIsCorrect && tokenPriceParameterIsCorrect
          val referenceMe = b.R7[Coll[Byte]].isDefined && b.R7[Coll[Byte]].get == SELF.id 
          val guardedByTheSameContract = b.propositionBytes == SELF.propositionBytes
          contractParametersAreCorrect && referenceMe && guardedByTheSameContract
        }

        val fullSpread = {
          spendingSellOrders.fold((returnTokenAmount, 0L), { (t: (Long, Long), sellOrder: Box) => 
            val returnTokensLeft = t._1
            val accumulatedFullSpread = t._2
            val sellOrderTokenPrice = sellOrder.R5[Long].get
            val sellOrderTokenAmount = sellOrder.tokens(0)._2
            if (sellOrder.creationInfo._1 >= SELF.creationInfo._1 && sellOrderTokenPrice <= tokenPrice) {
              // spread is ours
              val spreadPerToken = tokenPrice - sellOrderTokenPrice
              val tokenAmount = min(returnTokensLeft, sellOrderTokenAmount)
              val sellOrderSpread = spreadPerToken * tokenAmount
              (returnTokensLeft - tokenAmount, accumulatedFullSpread + sellOrderSpread)
            }
            else {
              // spread is not ours
              (returnTokensLeft - min(returnTokensLeft, sellOrderTokenAmount), accumulatedFullSpread)
            }
          })._2
        }

        val totalMatching = (SELF.value - expectedDexFee) == returnTokenAmount * tokenPrice && 
          returnBox.value >= fullSpread
        val partialMatching = {
          foundNewOrderBoxes.size == 1 && 
            foundNewOrderBoxes(0).value == (SELF.value - returnTokenAmount * tokenPrice - expectedDexFee) &&
            returnBox.value >= fullSpread
        }

        val coinsSecured = partialMatching || totalMatching

        val tokenIdIsCorrect = returnBox.tokens.getOrElse(0, (Coll[Byte](), 0L))._1 == tokenId
        
        allOf(Coll(
            tokenIdIsCorrect,
            returnTokenAmount >= 1,
            coinsSecured
        ))
      }
    }
      """.stripMargin

    contract(buyerContractEnv, buyerScript)
  }

  def sellerOrderContract(
    sellerParty: Party,
    token: TokenInfo,
    tokenPrice: Long,
    dexFeePerToken: Long
  ) = {

    val sellerPk = sellerParty.wallet.getAddress.pubKey

    val sellerContractEnv: ScriptEnv =
      Map("sellerPk" -> sellerPk, "tokenId" -> token.tokenId)

    val sellerScript = s""" sellerPk || {
      val tokenPrice = $tokenPrice
      val dexFeePerToken = $dexFeePerToken

      val selfTokenAmount = SELF.tokens(0)._2

      val returnBoxes = OUTPUTS.filter { (b: Box) => 
        b.R4[Coll[Byte]].isDefined && b.R4[Coll[Byte]].get == SELF.id && b.propositionBytes == sellerPk.propBytes
      }

      val boxesAreSortedByTokenPrice = { (boxes: Coll[Box]) => 
        boxes.fold((0L, true), { (t: (Long, Boolean), box: Box) => 
          val prevBoxTokenPrice = t._1
          val isSorted = t._2
          val boxTokenPrice = box.R5[Long].getOrElse(0L)
          (boxTokenPrice, isSorted && boxTokenPrice >= prevBoxTokenPrice)
        })._2
      }

      val spendingBuyOrders = INPUTS.filter { (b: Box) => 
        b.R4[Coll[Byte]].isDefined && b.R5[Long].isDefined && b.R6[Long].isDefined && {
          val buyOrderTokenId = b.R4[Coll[Byte]].get
          buyOrderTokenId == tokenId && b.tokens.size == 0 
        }
      }

      returnBoxes.size == 1 && spendingBuyOrders.size > 0 && boxesAreSortedByTokenPrice(spendingBuyOrders) && {
        val returnBox = returnBoxes(0)

        val foundNewOrderBoxes = OUTPUTS.filter { (b: Box) => 
          val tokenIdParameterIsCorrect = b.R4[Coll[Byte]].isDefined && b.R4[Coll[Byte]].get == tokenId 
          val tokenPriceParameterIsCorrect = b.R5[Long].isDefined && b.R5[Long].get == tokenPrice
          val dexFeePerTokenParameterIsCorrect = b.R6[Long].isDefined && b.R6[Long].get == dexFeePerToken
          val contractParametersAreCorrect = tokenIdParameterIsCorrect && tokenPriceParameterIsCorrect
          val referenceMe = b.R7[Coll[Byte]].isDefined && b.R7[Coll[Byte]].get == SELF.id 
          val guardedByTheSameContract = b.propositionBytes == SELF.propositionBytes
          contractParametersAreCorrect && referenceMe && guardedByTheSameContract
        }

        val fullSpread = { (tokenAmount: Long) =>
          spendingBuyOrders.fold((tokenAmount, 0L), { (t: (Long, Long), buyOrder: Box) => 
            val returnTokensLeft = t._1
            val accumulatedFullSpread = t._2
            val buyOrderTokenPrice = buyOrder.R5[Long].get
            val buyOrderDexFeePerToken = buyOrder.R6[Long].get
            val buyOrderTokenAmount = buyOrder.value / (buyOrderTokenPrice + buyOrderDexFeePerToken)
            if (buyOrder.creationInfo._1 > SELF.creationInfo._1 && buyOrderTokenPrice <= tokenPrice) {
              // spread is ours
              val spreadPerToken = tokenPrice - buyOrderTokenPrice
              val tokenAmountLeft = min(returnTokensLeft, buyOrderTokenAmount)
              val buyOrderSpread = spreadPerToken * tokenAmountLeft
              (returnTokensLeft - tokenAmountLeft, accumulatedFullSpread + buyOrderSpread)
            }
            else {
              // spread is not ours
              (returnTokensLeft - min(returnTokensLeft, buyOrderTokenAmount), accumulatedFullSpread)
            }
          })._2
        }

        val totalMatching = (returnBox.value == selfTokenAmount * tokenPrice + fullSpread(selfTokenAmount))

        val partialMatching = {
          foundNewOrderBoxes.size == 1 && {
            val newOrderBox = foundNewOrderBoxes(0)
            val newOrderTokenData = newOrderBox.tokens(0)
            val newOrderTokenAmount = newOrderTokenData._2
            val soldTokenAmount = selfTokenAmount - newOrderTokenAmount
            val minSoldTokenErgValue = soldTokenAmount * tokenPrice
            val expectedDexFee = dexFeePerToken * soldTokenAmount

            val newOrderTokenId = newOrderTokenData._1
            val tokenIdIsCorrect = newOrderTokenId == tokenId

            val newOrderValueIsCorrect = newOrderBox.value == (SELF.value - expectedDexFee)
            val returnBoxValueIsCorrect = returnBox.value == soldTokenAmount * tokenPrice + fullSpread(soldTokenAmount)
            tokenIdIsCorrect && soldTokenAmount >= 1 && newOrderValueIsCorrect && returnBoxValueIsCorrect
          }
        }

        (totalMatching || partialMatching) 
      }

      }""".stripMargin

    contract(sellerContractEnv, sellerScript)
  }

  def swapScenario = {

    val blockchainSim = newBlockChainSimulationScenario(
      "SwapWithPartialAndThenTotalMatching"
    )

    val token = blockchainSim.newToken("TKN")

    val buyerParty          = blockchainSim.newParty("buyer")
    val buyerBidTokenAmount = 100L
    val buyersBidTokenPrice = 5000000L
    val buyersBidNanoErgs   = buyersBidTokenPrice * buyerBidTokenAmount
    val buyerDexFee         = 10000000L
    val buyerDexFeePerToken = buyerDexFee / buyerBidTokenAmount
    val buyOrderTxFee       = MinTxFee
    val buyerSwapBoxValue   = MinErg

    buyerParty
      .generateUnspentBoxes(
        toSpend = buyersBidNanoErgs + buyOrderTxFee + buyerDexFee
      )

    val sellerParty          = blockchainSim.newParty("seller")
    val sellerAskTokenPrice  = 4000000L
    val sellerAskTokenAmount = 100L
    val sellerAskNanoErgs    = sellerAskTokenPrice * sellerAskTokenAmount
    val sellerDexFee         = 10000000L
    val sellerDexFeePerToken = sellerDexFee / sellerAskTokenAmount
    val sellOrderTxFee       = MinTxFee

    sellerParty.generateUnspentBoxes(
      toSpend       = sellOrderTxFee + sellerDexFee,
      tokensToSpend = List(token -> sellerAskTokenAmount)
    )

    sellerParty.printUnspentAssets()
    buyerParty.printUnspentAssets()

    val buyOrderContract =
      buyerContract(
        buyerParty,
        token,
        buyersBidTokenPrice,
        buyerDexFeePerToken
      )

    val buyOrderBoxValue = buyersBidTokenPrice * buyerBidTokenAmount + buyerDexFee
    val buyOrderBox = Box(
      value     = buyOrderBoxValue,
      script    = buyOrderContract,
      registers = R4 -> token.tokenId,
      R5 -> buyersBidTokenPrice,
      R6 -> buyerDexFeePerToken
    )

    val buyOrderTransaction = Transaction(
      inputs       = buyerParty.selectUnspentBoxes(toSpend = buyOrderBoxValue + buyOrderTxFee),
      outputs      = List(buyOrderBox),
      fee          = buyOrderTxFee,
      sendChangeTo = buyerParty.wallet.getAddress
    )

    val buyOrderTxSigned = buyerParty.wallet.sign(buyOrderTransaction)

    blockchainSim.send(buyOrderTxSigned)

    val sellOrderContract = sellerOrderContract(
      sellerParty,
      token,
      sellerAskTokenPrice,
      sellerDexFeePerToken
    )

    val sellOrderBox = Box(
      value     = sellerDexFee,
      token     = (token -> sellerAskTokenAmount),
      script    = sellOrderContract,
      registers = R4 -> token.tokenId,
      R5 -> sellerAskTokenPrice
    )

    val sellerBalanceBoxes = sellerParty.selectUnspentBoxes(
      toSpend       = sellerDexFee + sellOrderTxFee,
      tokensToSpend = List(token -> sellerAskTokenAmount)
    )

    val sellOrderTx = Transaction(
      inputs       = sellerBalanceBoxes,
      outputs      = List(sellOrderBox),
      fee          = sellOrderTxFee,
      sendChangeTo = sellerParty.wallet.getAddress
    )

    val sellOrderTxSigned = sellerParty.wallet.sign(sellOrderTx)

    blockchainSim.send(sellOrderTxSigned)

    val sellerTokenAmountSold       = sellerAskTokenAmount / 2
    val sellerDexFeeForPartialMatch = sellerDexFeePerToken * sellerTokenAmountSold
    val sellerOutBoxPartialMatch = Box(
      value     = sellerTokenAmountSold * sellerAskTokenPrice,
      registers = (R4 -> sellOrderTxSigned.outputs(0).id),
      script    = contract(sellerParty.wallet.getAddress.pubKey)
    )

    // reuse old contract, nothing is changed
    val newSellOrderContract = sellOrderContract

    val newSellOrderBox = Box(
      value     = sellOrderBox.value - sellerDexFeeForPartialMatch,
      token     = (token -> (sellerAskTokenAmount - sellerTokenAmountSold)),
      script    = newSellOrderContract,
      registers = R4 -> token.tokenId,
      R5 -> sellerAskTokenPrice,
      R6 -> sellerDexFeePerToken,
      R7 -> sellOrderTxSigned.outputs(0).id
    )

    val buyerTokenAmountBought     = buyerBidTokenAmount / 2
    val buyerDexFeeForPartialMatch = buyerDexFeePerToken * buyerTokenAmountBought
    val buyerOutBoxPartialMatch = Box(
      value     = buyerSwapBoxValue + (buyersBidTokenPrice - sellerAskTokenPrice) * buyerTokenAmountBought,
      token     = (token -> buyerTokenAmountBought),
      registers = (R4 -> buyOrderTxSigned.outputs(0).id),
      script    = contract(buyerParty.wallet.getAddress.pubKey)
    )

    // reuse old contract, nothing is changed
    val newBuyOrderContract = buyOrderContract

    val newBuyOrderBoxValue = buyOrderBox.value - buyerTokenAmountBought * buyersBidTokenPrice - buyerDexFeeForPartialMatch
    val newBuyOrderBox = Box(
      value     = newBuyOrderBoxValue,
      script    = newBuyOrderContract,
      registers = R4 -> token.tokenId,
      R5 -> buyersBidTokenPrice,
      R6 -> buyerDexFeePerToken,
      R7 -> buyOrderTxSigned.outputs(0).id
    )

    val dexParty = blockchainSim.newParty("DEX")

    val swapTxFee                = MinTxFee
    val dexFeeForPartialMatching = sellerDexFeeForPartialMatch + buyerDexFeeForPartialMatch - swapTxFee - buyerSwapBoxValue

    val dexFeeOutBoxForPartialMatching = Box(
      value  = dexFeeForPartialMatching,
      script = contract(dexParty.wallet.getAddress.pubKey)
    )

    val swapTxPartialMatching = Transaction(
      inputs = List(buyOrderTxSigned.outputs(0), sellOrderTxSigned.outputs(0)),
      outputs = List(
        buyerOutBoxPartialMatch,
        newBuyOrderBox,
        sellerOutBoxPartialMatch,
        newSellOrderBox,
        dexFeeOutBoxForPartialMatching
      ),
      fee = swapTxFee
    )

    val swapTxPartialMatchingSigned = dexParty.wallet.sign(swapTxPartialMatching)

    blockchainSim.send(swapTxPartialMatchingSigned)

    sellerParty.printUnspentAssets()
    buyerParty.printUnspentAssets()
    dexParty.printUnspentAssets()

    // ------------------------------------------------------------------------
    // Total matching
    // ------------------------------------------------------------------------

    val buyOrderAfterPartialMatching  = swapTxPartialMatchingSigned.outputs(1)
    val sellOrderAfterPartialMatching = swapTxPartialMatchingSigned.outputs(3)

    val sellerTokenAmountSoldInTotalMatching =
      sellOrderAfterPartialMatching.additionalTokens(0)._2
    val sellerDexFeeForTotalMatching = sellerDexFeePerToken * sellerTokenAmountSoldInTotalMatching
    val sellerOutBoxForTotalMatching = Box(
      value     = sellerTokenAmountSoldInTotalMatching * sellerAskTokenPrice,
      registers = (R4 -> sellOrderAfterPartialMatching.id),
      script    = contract(sellerParty.wallet.getAddress.pubKey)
    )

    val buyerTokenAmountBoughtInTotalMatching = sellerTokenAmountSoldInTotalMatching
    val buyerDexFeeForTotalMatching           = buyerDexFeePerToken * buyerTokenAmountBoughtInTotalMatching
    val buyerOutBoxForTotalMatching = Box(
      value     = buyerSwapBoxValue + (buyersBidTokenPrice - sellerAskTokenPrice) * buyerTokenAmountBoughtInTotalMatching,
      token     = (token -> buyerTokenAmountBoughtInTotalMatching),
      registers = (R4 -> buyOrderAfterPartialMatching.id),
      script    = contract(buyerParty.wallet.getAddress.pubKey)
    )

    val swapTxFeeForTotalMatching = MinTxFee
    val dexFeeForTotalMatching    = sellerDexFeeForTotalMatching + buyerDexFeeForTotalMatching - swapTxFeeForTotalMatching - buyerSwapBoxValue

    val dexFeeOutBoxForTotalMatching = Box(
      value  = dexFeeForTotalMatching,
      script = contract(dexParty.wallet.getAddress.pubKey)
    )

    val swapTxForTotalMatching = Transaction(
      inputs = List(buyOrderAfterPartialMatching, sellOrderAfterPartialMatching),
      outputs = List(
        buyerOutBoxForTotalMatching,
        sellerOutBoxForTotalMatching,
        dexFeeOutBoxForTotalMatching
      ),
      fee = swapTxFeeForTotalMatching
    )

    val swapTxForTotalMatchingSigned = dexParty.wallet.sign(swapTxForTotalMatching)

    blockchainSim.send(swapTxForTotalMatchingSigned)

    sellerParty.printUnspentAssets()
    buyerParty.printUnspentAssets()
    dexParty.printUnspentAssets()

  }

  def cancelBuyOrderScenario = {

    val blockchainSim = newBlockChainSimulationScenario("CancelBuyOrder")

    val token = blockchainSim.newToken("TKN")
    // as a workaround for https://github.com/ScorexFoundation/sigmastate-interpreter/issues/628

    val buyerParty          = blockchainSim.newParty("buyer")
    val buyerBidTokenAmount = 100L
    val buyersBidTokenPrice = 5000000L
    val buyersBidNanoErgs   = buyersBidTokenPrice * buyerBidTokenAmount
    val buyerDexFee         = 10000000L
    val buyerDexFeePerToken = buyerDexFee / buyerBidTokenAmount
    val buyOrderTxFee       = MinTxFee
    val buyerSwapBoxValue   = MinErg

    buyerParty
      .generateUnspentBoxes(
        toSpend = buyersBidNanoErgs + buyOrderTxFee + buyerDexFee
      )

    buyerParty.printUnspentAssets()

    val buyOrderContract =
      buyerContract(
        buyerParty,
        token,
        buyersBidTokenPrice,
        buyerDexFeePerToken
      )

    val buyOrderBoxValue = buyersBidTokenPrice * buyerBidTokenAmount + buyerDexFee
    val buyOrderBox = Box(
      value     = buyOrderBoxValue,
      script    = buyOrderContract,
      registers = R4 -> token.tokenId,
      R5 -> buyersBidTokenPrice,
      R6 -> buyerDexFeePerToken
    )

    val buyOrderTransaction = Transaction(
      inputs       = buyerParty.selectUnspentBoxes(toSpend = buyOrderBoxValue + buyOrderTxFee),
      outputs      = List(buyOrderBox),
      fee          = buyOrderTxFee,
      sendChangeTo = buyerParty.wallet.getAddress
    )

    val buyOrderTxSigned = buyerParty.wallet.sign(buyOrderTransaction)

    blockchainSim.send(buyOrderTxSigned)

    buyerParty.printUnspentAssets()

    val cancelTxFee = MinTxFee

    val buyerReturnBox = Box(
      value  = buyOrderBox.value - cancelTxFee,
      script = contract(buyerParty.wallet.getAddress.pubKey)
    )

    val cancelBuyTransaction = Transaction(
      inputs  = List(buyOrderTxSigned.outputs(0)),
      outputs = List(buyerReturnBox),
      fee     = cancelTxFee
    )

    val cancelBuyTransactionSigned = buyerParty.wallet.sign(cancelBuyTransaction)
    blockchainSim.send(cancelBuyTransactionSigned)

    buyerParty.printUnspentAssets()
  }

  def cancelSellOrderScenario = {

    val blockchainSim = newBlockChainSimulationScenario("CancelSellOrder")

    val token = blockchainSim.newToken("TKN")

    val sellerParty          = blockchainSim.newParty("seller")
    val sellerAskTokenPrice  = 5000000L
    val sellerAskTokenAmount = 100L
    val sellerAskNanoErgs    = sellerAskTokenPrice * sellerAskTokenAmount
    val sellerDexFee         = 10000000L
    val sellerDexFeePerToken = sellerDexFee / sellerAskTokenAmount
    val sellOrderTxFee       = MinTxFee

    sellerParty.generateUnspentBoxes(
      toSpend       = sellOrderTxFee + sellerDexFee,
      tokensToSpend = List(token -> sellerAskTokenAmount)
    )

    sellerParty.printUnspentAssets()

    val sellOrderContract = sellerOrderContract(
      sellerParty,
      token,
      sellerAskTokenPrice,
      sellerDexFeePerToken
    )

    val sellOrderBox = Box(
      value     = sellerDexFee,
      token     = (token -> sellerAskTokenAmount),
      script    = sellOrderContract,
      registers = R4 -> token.tokenId,
      R5 -> sellerAskTokenPrice
    )

    val sellerBalanceBoxes = sellerParty.selectUnspentBoxes(
      toSpend       = sellerDexFee + sellOrderTxFee,
      tokensToSpend = List(token -> sellerAskTokenAmount)
    )

    val sellOrderTx = Transaction(
      inputs       = sellerBalanceBoxes,
      outputs      = List(sellOrderBox),
      fee          = sellOrderTxFee,
      sendChangeTo = sellerParty.wallet.getAddress
    )

    val sellOrderTxSigned = sellerParty.wallet.sign(sellOrderTx)

    blockchainSim.send(sellOrderTxSigned)

    val cancelTxFee = MinTxFee

    val sellerReturnBox = Box(
      value  = sellOrderBox.value - cancelTxFee,
      token  = (token -> sellerAskTokenAmount),
      script = contract(sellerParty.wallet.getAddress.pubKey)
    )

    val cancelSellTransaction = Transaction(
      inputs  = List(sellOrderTxSigned.outputs(0)),
      outputs = List(sellerReturnBox),
      fee     = cancelTxFee
    )

    val cancelSellTransactionSigned = sellerParty.wallet.sign(cancelSellTransaction)

    blockchainSim.send(cancelSellTransactionSigned)

    sellerParty.printUnspentAssets()
  }

  swapScenario
  cancelSellOrderScenario
  cancelBuyOrderScenario
}
