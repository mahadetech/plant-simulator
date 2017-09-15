/*
 * Copyright (c) 2017 joesan @ http://github.com/joesan
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.inland24.plantsim.services.simulator.rampUpType

import com.inland24.plantsim.models.PowerPlantConfig.RampUpTypeConfig
import com.inland24.plantsim.models.PowerPlantType
import com.inland24.plantsim.services.simulator.rampUpType.PowerPlantState.{activePowerSignalKey, isAvailableSignalKey, isDispatchedSignalKey}
import org.joda.time.{DateTime, DateTimeZone}
import org.scalatest.FlatSpec

import scala.concurrent.duration._


class PowerPlantStateSimulatorSpec extends FlatSpec {

  val cfg = RampUpTypeConfig(
    id = 1,
    name = "RampUpType",
    minPower = 400.0,
    maxPower = 800.0,
    rampPowerRate = 100.0,
    rampRateInSeconds = 4.seconds,
    powerPlantType = PowerPlantType.RampUpType
  )

  behavior of PowerPlantState.getClass.getCanonicalName

  "PowerPlantState#empty" should "start with a default state" in {
    val emptyState = PowerPlantState.empty(cfg.id, cfg.minPower, cfg.maxPower, cfg.rampPowerRate, cfg.rampRateInSeconds)

    assert(emptyState.rampRate == cfg.rampPowerRate)
    assert(emptyState.powerPlantId == cfg.id)
    assert(emptyState.lastRampTime.getMillis <= DateTime.now(DateTimeZone.UTC).getMillis)
    assert(emptyState.signals.size === 0)
  }

  "PowerPlantState#init" should "initialize the default signals " +
    "(available = true, activePower = minPower, isDispatched = false)" in {
    val initState = PowerPlantState.init(
      PowerPlantState.empty(cfg.id, cfg.minPower, cfg.maxPower, cfg.rampPowerRate, cfg.rampRateInSeconds), cfg.minPower
    )

    assert(initState.signals.size === 3) // expecting 3 elements in the signals Map
    initState.signals.foreach {
      case (key, value) if key == PowerPlantState.isDispatchedSignalKey      => assert(!value.toBoolean)
      case (key, value) if key == PowerPlantState.isAvailableSignalKey => assert(value.toBoolean)
      case (key, value) if key == PowerPlantState.activePowerSignalKey => assert(value.toDouble === cfg.minPower)
    }

    assert(initState.setPoint === cfg.minPower)
    assert(initState.rampRate === cfg.rampPowerRate)
  }

  "PowerPlantState#dispatch" should "start dispatching the power plant according to its ramp rate" in {
    val initState = PowerPlantState.init(
      PowerPlantState.empty(cfg.id, cfg.minPower, cfg.maxPower, cfg.rampPowerRate, cfg.rampRateInSeconds), cfg.minPower
    )

    /*
     * Let's dispatch this Plant to its maxPower which is 800
     * The plant is currently operating at its minPower which is 400
     * and it has a rampRate of 100 in 4 seconds, so for it to go
     * from 400 to 800, it needs in total 16 seconds
     * Let us now test if this happens!
     * The first dispatch command should take its activePower to 500
     */
    val dispatchState1 = PowerPlantState.dispatch(initState.copy(setPoint = cfg.maxPower, lastRampTime = initState.lastRampTime.minusSeconds(4)))
    assert(dispatchState1.signals(PowerPlantState.activePowerSignalKey).toDouble === 500)
    // we then come back to the current time for the lastRampTime, so that we can do the next tests
    val reset1 = dispatchState1.copy(lastRampTime = DateTime.now(DateTimeZone.UTC))

    /*
     * On our second dispatch, we should go from 500 to 600, but we got to wait 4 seconds
     * Blocking may be a bad idea, so we simulate time (i.e., subtract 4 seconds to the isRampUp check)
     */
    val dispatchState2 = PowerPlantState.dispatch(reset1.copy(lastRampTime = dispatchState1.lastRampTime.minusSeconds(4)))
    assert(dispatchState2.signals(PowerPlantState.activePowerSignalKey).toDouble === 600)
    val reset2 = dispatchState2.copy(lastRampTime = DateTime.now(DateTimeZone.UTC))

    // Let's try another dispatch immediately, this should have no effect and we should still stay at 600
    val dispatchState2_copy = PowerPlantState.dispatch(reset2.copy(lastRampTime = reset2.lastRampTime.plusSeconds(1)))
    assert(reset2.signals === dispatchState2_copy.signals)

    // Another 4 seconds elapse, we move to 700
    val dispatchState3 = PowerPlantState.dispatch(dispatchState2.copy(lastRampTime = dispatchState2.lastRampTime.minusSeconds(4)))
    assert(dispatchState3.signals(PowerPlantState.activePowerSignalKey).toDouble === 700)

    // Another 4 seconds elapse, we move to 800, our setPoint
    val dispatchState4 = PowerPlantState.dispatch(dispatchState3.copy(lastRampTime = dispatchState3.lastRampTime.minusSeconds(4)))
    assert(dispatchState4.signals(PowerPlantState.activePowerSignalKey).toDouble === 800)
  }

  "PowerPlantState#returnToNormal" should "start ramping down the power plant according to its ramp rate" in {
    // The init state is a dispatched state with maxPower so that we could ReturnToNormal from that
    val dispatchedState = PowerPlantState(
      powerPlantId = 1,
      setPoint = cfg.maxPower,
      minPower = cfg.minPower,
      maxPower = cfg.maxPower,
      // Here we assume that this PowerPlant was up and running since 20 seconds
      lastRampTime = DateTime.now(DateTimeZone.UTC).minusSeconds(20),
      rampRate = cfg.rampPowerRate,
      rampRateInSeconds = cfg.rampRateInSeconds,
      signals = Map(
        activePowerSignalKey  -> cfg.maxPower.toString,
        isDispatchedSignalKey -> true.toString, // when in dispatched this is true
        isAvailableSignalKey  -> true.toString // indicates if the power plant is not available for steering
      )
    )

    /*
     * Let's ReturnToNormal this Plant which is returning to its minPower
     * The plant is currently operating at its maxPower which is 800
     * and it has a rampRate of 100 in 4 seconds, which means to go down from
     * 800 to 700 it needs 4 seconds and so on
     * Let us now test if this happens!
     * The first ReturnToNormal command should take its activePower to 700
     */
    val rtnState1 = PowerPlantState.returnToNormal(dispatchedState)
    assert(rtnState1.signals(PowerPlantState.activePowerSignalKey).toDouble === 700.0)
    // we now come back to the current time for the lastRampTime, so that we can do the next tests
    val reset1 = rtnState1.copy(lastRampTime = DateTime.now(DateTimeZone.UTC))

    /*
     * On our second ReturnToNormal, we should go from 700.0 to 600.0, but we got to wait 4 seconds
     * Blocking may be a bad idea, so we simulate time (i.e., subtract 4 seconds to the isRampUp check)
     */
    val rtnState2 = PowerPlantState.returnToNormal(reset1.copy(lastRampTime = rtnState1.lastRampTime.minusSeconds(4)))
    assert(rtnState2.signals(PowerPlantState.activePowerSignalKey).toDouble === 600.0)
    val reset2 = rtnState2.copy(lastRampTime = DateTime.now(DateTimeZone.UTC))

    // Let's try another ReturnToNormal immediately, this should have no effect and we should still stay at 600.0
    val rtnState2_copy = PowerPlantState.returnToNormal(reset2.copy(lastRampTime = reset2.lastRampTime.plusSeconds(1)))
    assert(reset2.signals === rtnState2_copy.signals)

    // Another 4 seconds elapse, we move to 500.0
    val rtnState3 = PowerPlantState.returnToNormal(rtnState2.copy(lastRampTime = rtnState2.lastRampTime.minusSeconds(4)))
    assert(rtnState3.signals(PowerPlantState.activePowerSignalKey).toDouble === 500)

    // Another 4 seconds elapse, we move to 400.0, our minPower to which we ReturnToNormal to
    val rtnState4 = PowerPlantState.returnToNormal(rtnState3.copy(lastRampTime = rtnState3.lastRampTime.minusSeconds(4)))
    assert(rtnState4.signals(PowerPlantState.activePowerSignalKey).toDouble === cfg.minPower)
  }
}