package org.bitcoins.core.protocol.dlc

import org.bitcoins.core.currency.{CurrencyUnit, Satoshis}
import org.bitcoins.core.protocol.tlv.{PayoutFunctionV0TLV, TLVPoint}
import org.bitcoins.core.util.{Indexed, NumberUtil}

import scala.math.BigDecimal.RoundingMode

/** A DLC payout curve defined by piecewise interpolating points */
case class DLCPayoutCurve(points: Vector[OutcomePayoutPoint]) {
  require(points.init.zip(points.tail).forall { case (p1, p2) =>
            p1.outcome < p2.outcome
          },
          s"Points must be ascending: $points")

  def toTLV: PayoutFunctionV0TLV = {
    PayoutFunctionV0TLV(points.map { point =>
      TLVPoint(point.outcome,
               point.roundedPayout,
               point.extraPrecision,
               point.isEndpoint)
    })
  }

  /** These points (and their indices in this.points) represent the endpoints
    * between which interpolation happens.
    * In other words these endpoints define the pieces of the piecewise function.
    */
  lazy val endpoints: Vector[Indexed[OutcomePayoutPoint]] =
    Indexed(points).filter(_.element.isEndpoint)

  /** This Vector contains the function pieces between the endpoints */
  lazy val functionComponents: Vector[DLCPayoutCurvePiece] = {
    endpoints.init.zip(endpoints.tail).map { // All pairs of adjacent endpoints
      case (Indexed(_, index), Indexed(_, nextIndex)) =>
        DLCPayoutCurvePiece(points.slice(index, nextIndex + 1))
    }
  }

  private lazy val outcomes = endpoints.map(_.element.outcome)

  /** Returns the function component on which the given oracle outcome is
    * defined, along with its index
    */
  def componentFor(outcome: Long): Indexed[DLCPayoutCurvePiece] = {
    val endpointIndex = NumberUtil.search(outcomes, outcome)
    val Indexed(endpoint, _) = endpoints(endpointIndex)

    if (
      endpoint.outcome == outcome && endpointIndex != functionComponents.length
    ) {
      Indexed(functionComponents(endpointIndex), endpointIndex)
    } else {
      Indexed(functionComponents(endpointIndex - 1), endpointIndex - 1)
    }
  }

  def getPayout(outcome: Long): Satoshis = {
    val Indexed(func, _) = componentFor(outcome)
    func(outcome)
  }

  def getPayout(outcome: Long, rounding: RoundingIntervals): Satoshis = {
    val Indexed(func, _) = componentFor(outcome)
    func(outcome, rounding)
  }

  def getPayout(
      outcome: Long,
      rounding: RoundingIntervals,
      totalCollateral: Satoshis): Satoshis = {
    val Indexed(func, _) = componentFor(outcome)
    func(outcome, rounding, totalCollateral)
  }

  def apply(outcome: Long): Satoshis = getPayout(outcome)

  def apply(outcome: Long, rounding: RoundingIntervals): Satoshis =
    getPayout(outcome, rounding)

  def apply(
      outcome: Long,
      rounding: RoundingIntervals,
      totalCollateral: Satoshis): Satoshis =
    getPayout(outcome, rounding, totalCollateral)
}

object DLCPayoutCurve {

  def fromTLV(tlv: PayoutFunctionV0TLV): DLCPayoutCurve = {
    DLCPayoutCurve(tlv.points.map { point =>
      val payoutWithPrecision =
        point.value.toLong + (BigDecimal(point.extraPrecision) / (1 << 16))
      OutcomePayoutPoint(point.outcome, payoutWithPrecision, point.isEndpoint)
    })
  }
}

/** A point on a DLC payout curve to be used for interpolation
  *
  * outcome: An element of the domain of possible events signed by the oracle
  * payout: The payout to the local party corresponding to outcome
  * isEndpoint: True if this point defines a boundary between pieces in the curve
  */
sealed trait OutcomePayoutPoint {
  def outcome: Long
  def payout: BigDecimal
  def isEndpoint: Boolean

  def roundedPayout: Satoshis = {
    Satoshis(payout.setScale(0, RoundingMode.FLOOR).toLongExact)
  }

  def extraPrecision: Int = {
    val shifted = (payout - roundedPayout.toLong) * (1 << 16)
    shifted.setScale(0, RoundingMode.FLOOR).toIntExact
  }

  def copy(
      outcome: Long = this.outcome,
      payout: BigDecimal = this.payout): OutcomePayoutPoint = {
    this match {
      case OutcomePayoutEndpoint(_, _) => OutcomePayoutEndpoint(outcome, payout)
      case OutcomePayoutMidpoint(_, _) => OutcomePayoutMidpoint(outcome, payout)
    }
  }
}

object OutcomePayoutPoint {

  def apply(
      outcome: Long,
      payout: BigDecimal,
      isEndpoint: Boolean): OutcomePayoutPoint = {
    if (isEndpoint) {
      OutcomePayoutEndpoint(outcome, payout)
    } else {
      OutcomePayoutMidpoint(outcome, payout)
    }
  }

  def apply(
      outcome: Long,
      payout: Satoshis,
      isEndpoint: Boolean): OutcomePayoutPoint = {
    OutcomePayoutPoint(outcome, payout.toLong, isEndpoint)
  }
}

case class OutcomePayoutEndpoint(outcome: Long, payout: BigDecimal)
    extends OutcomePayoutPoint {
  override val isEndpoint: Boolean = true

  def toMidpoint: OutcomePayoutMidpoint = OutcomePayoutMidpoint(outcome, payout)
}

object OutcomePayoutEndpoint {

  def apply(outcome: Long, payout: CurrencyUnit): OutcomePayoutEndpoint = {
    OutcomePayoutEndpoint(outcome, payout.satoshis.toLong)
  }
}

case class OutcomePayoutMidpoint(outcome: Long, payout: BigDecimal)
    extends OutcomePayoutPoint {
  override val isEndpoint: Boolean = false

  def toEndpoint: OutcomePayoutEndpoint = OutcomePayoutEndpoint(outcome, payout)
}

object OutcomePayoutMidpoint {

  def apply(outcome: Long, payout: Satoshis): OutcomePayoutMidpoint = {
    OutcomePayoutMidpoint(outcome, payout.toLong)
  }
}

/** A single piece of a larger piecewise function defined between left and right endpoints */
sealed trait DLCPayoutCurvePiece {
  def leftEndpoint: OutcomePayoutEndpoint
  def midpoints: Vector[OutcomePayoutMidpoint]
  def rightEndpoint: OutcomePayoutEndpoint

  midpoints.headOption match {
    case Some(firstMidpoint) =>
      require(leftEndpoint.outcome < firstMidpoint.outcome,
              s"Points must be ascending: $this")
      require(midpoints.init.zip(midpoints.tail).forall { case (m1, m2) =>
                m1.outcome < m2.outcome
              },
              s"Points must be ascending: $this")
      require(rightEndpoint.outcome > midpoints.last.outcome,
              s"Points must be ascending: $this")
    case None =>
      require(leftEndpoint.outcome < rightEndpoint.outcome,
              s"Points must be ascending: $this")
  }

  def apply(outcome: Long): Satoshis

  def apply(outcome: Long, rounding: RoundingIntervals): Satoshis = {
    rounding.round(outcome, apply(outcome))
  }

  def apply(
      outcome: Long,
      rounding: RoundingIntervals,
      totalCollateral: Satoshis): Satoshis = {
    val rounded = rounding.round(outcome, apply(outcome)).toLong
    val modified = math.min(math.max(rounded, 0), totalCollateral.toLong)

    Satoshis(modified)
  }

  /** Returns the largest Long less than or equal to bd (floor function) */
  protected def bigDecimalSats(bd: BigDecimal): Satoshis = {
    Satoshis(
      bd.setScale(6, RoundingMode.HALF_UP)
        .setScale(0, RoundingMode.FLOOR)
        .toLongExact)
  }
}

object DLCPayoutCurvePiece {

  def apply(points: Vector[OutcomePayoutPoint]): DLCPayoutCurvePiece = {
    require(points.head.isEndpoint && points.last.isEndpoint,
            s"First and last points must be endpoints, $points")
    require(points.tail.init.forall(!_.isEndpoint),
            s"Endpoint detected in middle, $points")

    points match {
      case Vector(left: OutcomePayoutEndpoint, right: OutcomePayoutEndpoint) =>
        if (left.payout == right.payout) {
          OutcomePayoutConstant(left, right)
        } else {
          OutcomePayoutLine(left, right)
        }
      case Vector(left: OutcomePayoutEndpoint,
                  mid: OutcomePayoutMidpoint,
                  right: OutcomePayoutEndpoint) =>
        OutcomePayoutQuadratic(left, mid, right)
      case Vector(left: OutcomePayoutEndpoint,
                  mid1: OutcomePayoutMidpoint,
                  mid2: OutcomePayoutMidpoint,
                  right: OutcomePayoutEndpoint) =>
        OutcomePayoutCubic(left, mid1, mid2, right)
      case _ => OutcomePayoutPolynomial(points)
    }
  }
}

case class OutcomePayoutConstant(
    leftEndpoint: OutcomePayoutEndpoint,
    rightEndpoint: OutcomePayoutEndpoint)
    extends DLCPayoutCurvePiece {
  require(leftEndpoint.payout == rightEndpoint.payout,
          "Constant function must have same values on endpoints")

  override lazy val midpoints: Vector[OutcomePayoutMidpoint] = Vector.empty

  override def apply(outcome: Long): Satoshis =
    bigDecimalSats(leftEndpoint.payout)
}

/** A Line between left and right endpoints defining a piece of a larger payout curve */
case class OutcomePayoutLine(
    leftEndpoint: OutcomePayoutEndpoint,
    rightEndpoint: OutcomePayoutEndpoint)
    extends DLCPayoutCurvePiece {
  override lazy val midpoints: Vector[OutcomePayoutMidpoint] = Vector.empty

  lazy val slope: BigDecimal = {
    (rightEndpoint.payout - leftEndpoint.payout) / (rightEndpoint.outcome - leftEndpoint.outcome)
  }

  override def apply(outcome: Long): Satoshis = {
    val value =
      (outcome - leftEndpoint.outcome) * slope + leftEndpoint.payout

    bigDecimalSats(value)
  }
}

/** A quadratic between left and right endpoints defining a piece of a larger payout curve.
  * A quadratic equation defines a parabola: https://en.wikipedia.org/wiki/Quadratic_function
  */
case class OutcomePayoutQuadratic(
    leftEndpoint: OutcomePayoutEndpoint,
    midpoint: OutcomePayoutMidpoint,
    rightEndpoint: OutcomePayoutEndpoint)
    extends DLCPayoutCurvePiece {
  override lazy val midpoints: Vector[OutcomePayoutMidpoint] = Vector(midpoint)

  private lazy val (x01, x02, x12) =
    (leftEndpoint.outcome - midpoint.outcome,
     leftEndpoint.outcome - rightEndpoint.outcome,
     midpoint.outcome - rightEndpoint.outcome)

  private lazy val (x10, x20, x21) = (-x01, -x02, -x12)

  private lazy val (y0, y1, y2) =
    (leftEndpoint.payout, midpoint.payout, rightEndpoint.payout)

  private lazy val (c0, c1, c2) =
    (y0 / (x01 * x02), y1 / (x10 * x12), y2 / (x20 * x21))

  override def apply(outcome: Long): Satoshis = {
    val x0 = outcome - leftEndpoint.outcome
    val x1 = outcome - midpoint.outcome
    val x2 = outcome - rightEndpoint.outcome

    val value = c0 * (x1 * x2) + c1 * (x0 * x2) + c2 * (x0 * x1)

    bigDecimalSats(value)
  }
}

/** A cubic between left and right endpoints defining a piece of a larger payout curve */
case class OutcomePayoutCubic(
    leftEndpoint: OutcomePayoutEndpoint,
    leftMidpoint: OutcomePayoutMidpoint,
    rightMidpoint: OutcomePayoutMidpoint,
    rightEndpoint: OutcomePayoutEndpoint)
    extends DLCPayoutCurvePiece {

  override lazy val midpoints: Vector[OutcomePayoutMidpoint] =
    Vector(leftMidpoint, rightMidpoint)

  private lazy val (x01, x02, x03, x12, x13, x23) =
    (leftEndpoint.outcome - leftMidpoint.outcome,
     leftEndpoint.outcome - rightMidpoint.outcome,
     leftEndpoint.outcome - rightEndpoint.outcome,
     leftMidpoint.outcome - rightMidpoint.outcome,
     leftMidpoint.outcome - rightEndpoint.outcome,
     rightMidpoint.outcome - rightEndpoint.outcome)

  private lazy val (x10, x20, x30, x21, x31, x32) =
    (-x01, -x02, -x03, -x12, -x13, -x23)

  private lazy val (y0, y1, y2, y3) = (leftEndpoint.payout,
                                       leftMidpoint.payout,
                                       rightMidpoint.payout,
                                       rightEndpoint.payout)

  private lazy val (c0, c1, c2, c3) =
    (y0 / (x01 * x02 * x03),
     y1 / (x10 * x12 * x13),
     y2 / (x20 * x21 * x23),
     y3 / (x30 * x31 * x32))

  override def apply(outcome: Long): Satoshis = {
    val x0 = outcome - leftEndpoint.outcome
    val x1 = outcome - leftMidpoint.outcome
    val x2 = outcome - rightMidpoint.outcome
    val x3 = outcome - rightEndpoint.outcome

    val value =
      c0 * (x1 * x2 * x3) + c1 * (x0 * x2 * x3) + c2 * (x0 * x1 * x3) + c3 * (x0 * x1 * x2)

    bigDecimalSats(value)
  }
}

/** A polynomial interpolating points and defining a piece of a larger payout curve */
case class OutcomePayoutPolynomial(points: Vector[OutcomePayoutPoint])
    extends DLCPayoutCurvePiece {
  require(points.head.isEndpoint && points.last.isEndpoint,
          s"First and last points must be endpoints, $points")
  require(points.tail.init.forall(!_.isEndpoint),
          s"Endpoint detected in middle, $points")

  override lazy val leftEndpoint: OutcomePayoutEndpoint =
    points.head.asInstanceOf[OutcomePayoutEndpoint]

  override lazy val rightEndpoint: OutcomePayoutEndpoint =
    points.last.asInstanceOf[OutcomePayoutEndpoint]

  override lazy val midpoints: Vector[OutcomePayoutMidpoint] =
    points.tail.init.asInstanceOf[Vector[OutcomePayoutMidpoint]]

  lazy val coefficients: Vector[BigDecimal] = {
    points.map { point =>
      val xi = point.outcome
      val yi = point.payout

      val denom = points.foldLeft(BigDecimal(1)) { case (prodSoFar, p) =>
        val xj = p.outcome

        if (xj == xi) {
          prodSoFar
        } else {
          prodSoFar * (xi - xj)
        }
      }

      yi / denom
    }
  }

  override def apply(outcome: Long): Satoshis = {
    points.find(_.outcome == outcome) match {
      case Some(point) => bigDecimalSats(point.payout)
      case None =>
        val allProd = points.foldLeft(BigDecimal(1)) {
          case (prodSoFar, point) =>
            prodSoFar * (outcome - point.outcome)
        }

        val value = coefficients.zipWithIndex.foldLeft(BigDecimal(0)) {
          case (sumSoFar, (coefficientI, i)) =>
            sumSoFar + (coefficientI * allProd / (outcome - points(i).outcome))
        }

        bigDecimalSats(value)
    }
  }
}
