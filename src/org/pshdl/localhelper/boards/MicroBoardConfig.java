/*******************************************************************************
 * PSHDL is a library and (trans-)compiler for PSHDL input. It generates
 *     output suitable for implementation or simulation of it.
 *
 *     Copyright (C) 2014 Karsten Becker (feedback (at) pshdl (dot) org)
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 *     This License does not grant permission to use the trade names, trademarks,
 *     service marks, or product names of the Licensor, except as required for
 *     reasonable and customary use in describing the origin of the Work.
 *
 * Contributors:
 *     Karsten Becker - initial API and implementation
 ******************************************************************************/
package org.pshdl.localhelper.boards;

import java.io.IOException;
import java.util.Map;

import org.pshdl.localhelper.JSONHelper;
import org.pshdl.model.HDLVariableDeclaration.HDLDirection;
import org.pshdl.rest.models.settings.BoardSpecSettings;
import org.pshdl.rest.models.settings.BoardSpecSettings.FPGASpec;
import org.pshdl.rest.models.settings.BoardSpecSettings.PinSpec;
import org.pshdl.rest.models.settings.BoardSpecSettings.PinSpec.Polarity;
import org.pshdl.rest.models.settings.BoardSpecSettings.PinSpec.TimeSpec;
import org.pshdl.rest.models.settings.BoardSpecSettings.PinSpecGroup;

import com.google.common.collect.Maps;

public class MicroBoardConfig {

	public static BoardSpecSettings createMicroBoard() {
		final FPGASpec fpga = new FPGASpec("Xilinx", "Spartan 6", "XC6SLX9-2CSG324C");
		final PinSpecGroup clkRst = createResetClckGroup();
		final PinSpecGroup dips = createGPIODip();
		final PinSpecGroup leds = createGPIOLED();
		final PinSpecGroup uart = createUART();
		final PinSpecGroup i2c = createI2C();
		final PinSpecGroup pmod1 = createPMod1();
		final PinSpecGroup pmod2 = createPMod2();
		final BoardSpecSettings microBoard = new BoardSpecSettings(
				"Avnet Spartan-6 LX9 MicroBoard",
				"The low-cost Spartan-6 FPGA LX9 MicroBoard is the perfect solution for designers interested in exploring the MicroBlaze soft processor or Spartan-6 FPGAs in general. The kit comes with several pre-built MicroBlaze “systems” allowing users to start software development just like any standard off-the-shelf microprocessor. The included Software Development Kit (SDK) provides a familiar Eclipse-based environment for writing and debugging code. Experienced FPGA users will find the MicroBoard a valuable tool for general purpose prototyping and testing. The included peripherals and expansion interfaces make the kit ideal for a wide variety of applications. From a system running an RTOS to a Linux-based web server, the Spartan-6 LX9 MicroBoard can help you validate your next design idea.",
				fpga, null, clkRst, dips, leds, uart, i2c, pmod1, pmod2);
		return microBoard;
	}

	private static PinSpecGroup createPMod2() {
		final Map<String, String> defAttribute = Maps.newHashMap();
		defAttribute.put(PinSpec.IOSTANDARD, "LVCMOS33");
		final PinSpec p1 = new PinSpec("PMOD2_P1", "H12", "Pin 1 of PMOD interface", defAttribute, null, null, HDLDirection.INOUT);
		final PinSpec p2 = new PinSpec("PMOD2_P2", "G13", "Pin 2 of PMOD interface", defAttribute, null, null, HDLDirection.INOUT);
		final PinSpec p3 = new PinSpec("PMOD2_P3", "E16", "Pin 3 of PMOD interface", defAttribute, null, null, HDLDirection.INOUT);
		final PinSpec p4 = new PinSpec("PMOD2_P4", "E18", "Pin 4 of PMOD interface", defAttribute, null, null, HDLDirection.INOUT);
		final PinSpec p7 = new PinSpec("PMOD2_P7", "K12", "Pin 7 of PMOD interface", defAttribute, null, null, HDLDirection.INOUT);
		final PinSpec p8 = new PinSpec("PMOD2_P8", "K13", "Pin 8 of PMOD interface", defAttribute, null, null, HDLDirection.INOUT);
		final PinSpec p9 = new PinSpec("PMOD2_P9", "F17", "Pin 9 of PMOD interface", defAttribute, null, null, HDLDirection.INOUT);
		final PinSpec p10 = new PinSpec("PMOD2_P10", "F18", "Pin 10 of PMOD interface", defAttribute, null, null, HDLDirection.INOUT);
		return new PinSpecGroup("PMOD 2", "Peripheral Modules and GPIO at connector J4. See https://www.digilentinc.com/PMODs", p1, p2, p3, p4, p7, p8, p9, p10);
	}

	private static PinSpecGroup createPMod1() {
		final Map<String, String> defAttribute = Maps.newHashMap();
		defAttribute.put(PinSpec.IOSTANDARD, "LVCMOS33");
		final PinSpec p1 = new PinSpec("PMOD1_P1", "F15", "Pin 1 of PMOD interface", defAttribute, null, null, HDLDirection.INOUT);
		final PinSpec p2 = new PinSpec("PMOD1_P2", "F16", "Pin 2 of PMOD interface", defAttribute, null, null, HDLDirection.INOUT);
		final PinSpec p3 = new PinSpec("PMOD1_P3", "C17", "Pin 3 of PMOD interface", defAttribute, null, null, HDLDirection.INOUT);
		final PinSpec p4 = new PinSpec("PMOD1_P4", "C18", "Pin 4 of PMOD interface", defAttribute, null, null, HDLDirection.INOUT);
		final PinSpec p7 = new PinSpec("PMOD1_P7", "F14", "Pin 7 of PMOD interface", defAttribute, null, null, HDLDirection.INOUT);
		final PinSpec p8 = new PinSpec("PMOD1_P8", "G14", "Pin 8 of PMOD interface", defAttribute, null, null, HDLDirection.INOUT);
		final PinSpec p9 = new PinSpec("PMOD1_P9", "D17", "Pin 9 of PMOD interface", defAttribute, null, null, HDLDirection.INOUT);
		final PinSpec p10 = new PinSpec("PMOD1_P10", "D18", "Pin 10 of PMOD interface", defAttribute, null, null, HDLDirection.INOUT);
		return new PinSpecGroup("PMOD 1", "Peripheral Modules and GPIO at connector J5. See https://www.digilentinc.com/PMODs", p1, p2, p3, p4, p7, p8, p9, p10);
	}

	private static PinSpecGroup createI2C() {
		final Map<String, String> defAttribute = Maps.newHashMap();
		defAttribute.put(PinSpec.IOSTANDARD, "LVCMOS33");
		defAttribute.put(PinSpec.PULL, PinSpec.PULL_UP);
		final PinSpec scl = new PinSpec("scl", "P12", "SCL for I2C", defAttribute, null, Polarity.active_low, HDLDirection.INOUT);
		final PinSpec sda = new PinSpec("sda", "U13", "SDA for I2C", defAttribute, null, Polarity.active_low, HDLDirection.INOUT);
		return new PinSpecGroup("I2C for CDE913", "Texas Instruments CDCE913 programming port. Internal pull-ups required since external resistors are not populated", scl, sda);
	}

	private static PinSpecGroup createUART() {
		final Map<String, String> defAttribute = Maps.newHashMap();
		defAttribute.put(PinSpec.IOSTANDARD, "LVCMOS33");
		final PinSpec rxd = new PinSpec("usb_rs232_rxd", "R7", "RXD, the receiver input on the FPGA, the serial output of the chip", defAttribute, null, null, HDLDirection.IN);
		final PinSpec txd = new PinSpec("usb_rs232_txd", "T7", "TXD, the sender output on the FPGA, the serial input of the chip", defAttribute, null, null, HDLDirection.OUT);
		return new PinSpecGroup("UART", "Silicon Labs CP2102 USB-to-UART Bridge Chip", rxd, txd);
	}

	private static PinSpecGroup createGPIODip() {
		final Map<String, String> defAttribute = Maps.newHashMap();
		defAttribute.put(PinSpec.IOSTANDARD, "LVCMOS33");
		defAttribute.put(PinSpec.PULL, PinSpec.PULL_DOWN);
		final PinSpec dip0 = new PinSpec("gpio_dip[0]", "B3", "GPIO Dip switch 0", defAttribute, null, Polarity.active_high, HDLDirection.IN);
		final PinSpec dip1 = new PinSpec("gpio_dip[1]", "A3", "GPIO Dip switch 1", defAttribute, null, Polarity.active_high, HDLDirection.IN);
		final PinSpec dip2 = new PinSpec("gpio_dip[2]", "B4", "GPIO Dip switch 2", defAttribute, null, Polarity.active_high, HDLDirection.IN);
		final PinSpec dip3 = new PinSpec("gpio_dip[3]", "A4", "GPIO Dip switch 3", defAttribute, null, Polarity.active_high, HDLDirection.IN);
		return new PinSpecGroup("GPIO Dips", "User DIP Switch x4,Internal pull-down required since external resistor is not populated", dip0, dip1, dip2, dip3);
	}

	private static PinSpecGroup createGPIOLED() {
		final Map<String, String> defAttribute = Maps.newHashMap();
		defAttribute.put(PinSpec.IOSTANDARD, "LVCMOS18");
		final PinSpec led0 = new PinSpec("gpio_led[0]", "P4", "GPIO LED 0", defAttribute, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec led1 = new PinSpec("gpio_led[1]", "L6", "GPIO LED 1", defAttribute, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec led2 = new PinSpec("gpio_led[2]", "F5", "GPIO LED 2", defAttribute, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec led3 = new PinSpec("gpio_led[3]", "C2", "GPIO LED 3", defAttribute, null, Polarity.active_low, HDLDirection.OUT);
		return new PinSpecGroup("GPIO Dips", "User LEDs", led0, led1, led2, led3);
	}

	private static PinSpecGroup createResetClckGroup() {
		final Map<String, String> defAttributes = Maps.newHashMap();
		defAttributes.put(PinSpec.IOSTANDARD, "LVCMOS33");
		final PinSpec clk = new PinSpec("user_clock", "V10", "40 MHz, USER_CLOCK can be used as external configuration clock", defAttributes, new TimeSpec("40000", "KHz"), null,
				HDLDirection.IN);
		clk.assignedSignal = "$clk";
		final PinSpec clk_2 = new PinSpec("clock_y2", "K15", "66.667 MHz", defAttributes, new TimeSpec("66666.7", "KHz"), null, HDLDirection.IN);
		final PinSpec clk_3 = new PinSpec("clock_y3", "C10", "100 MHz", defAttributes, new TimeSpec("100000", "KHz"), null, HDLDirection.IN);
		final PinSpec backup_clock = new PinSpec("backup_clock", "R8",
				"The following oscillator is not populated in production but the footprint is compatible with the Maxim DS1088LU", defAttributes, null, null, HDLDirection.IN);
		final Map<String, String> rstAttribute = Maps.newHashMap();
		rstAttribute.put(PinSpec.IOSTANDARD, "LVCMOS33");
		rstAttribute.put("CLOCK_DEDICATED_ROUTE", "false");
		rstAttribute.put(PinSpec.PULL, PinSpec.PULL_DOWN);
		rstAttribute.put("TIG", PinSpec.NO_VALUE);
		final PinSpec rst = new PinSpec("user_reset", "V4", "User Reset Push Button. Internal pull-down required since external resistor is not populated", rstAttribute, null,
				Polarity.active_high, HDLDirection.IN);
		rst.assignedSignal = "$rst";
		return new PinSpecGroup("Clock/Reset", "Clock and reset signals", clk, rst, clk_2, clk_3, backup_clock);
	}

	public static void main(String[] args) throws IOException {
		JSONHelper.getWriter().writeValue(System.out, createMicroBoard());
	}
}
