package com.thoughtworks.future.scalatest

import com.thoughtworks.future.Continuation
import org.scalatest._

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
class FutureFreeSpecSpec extends FutureFreeSpec with Matchers {

  "Continuation should be allowed" in {
    Continuation[Assertion, Unit] {
      42 should be(42)
    }
  }

}
