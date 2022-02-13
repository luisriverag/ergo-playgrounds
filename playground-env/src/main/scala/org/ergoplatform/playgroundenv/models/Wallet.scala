package org.ergoplatform.playgroundenv.models

import org.ergoplatform.{ErgoLikeTransaction, UnsignedErgoLikeTransaction}
import sigmastate.basics.ProveDHTuple

trait Wallet {

  def name: String

  def getAddress: Address

  def sign(tx: UnsignedErgoLikeTransaction): ErgoLikeTransaction

  def signWithDHTData(proveDHTuple: ProveDHTuple, tx: UnsignedErgoLikeTransaction): ErgoLikeTransaction
}
