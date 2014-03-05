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

import java.io.*;
import java.util.*;
import java.util.regex.*;

import javax.xml.parsers.*;

import org.pshdl.localhelper.*;
import org.pshdl.model.HDLVariableDeclaration.HDLDirection;
import org.pshdl.rest.models.settings.*;
import org.pshdl.rest.models.settings.BoardSpecSettings.FPGASpec;
import org.pshdl.rest.models.settings.BoardSpecSettings.PinSpec;
import org.pshdl.rest.models.settings.BoardSpecSettings.PinSpec.Polarity;
import org.pshdl.rest.models.settings.BoardSpecSettings.PinSpec.TimeSpec;
import org.pshdl.rest.models.settings.BoardSpecSettings.PinSpecGroup;
import org.xml.sax.*;
import org.xml.sax.helpers.*;

import com.fasterxml.jackson.databind.*;
import com.google.common.base.*;
import com.google.common.collect.*;
import com.google.common.io.*;

public class PSHDLBoardConfig {

	public static BoardSpecSettings generateBoardSpec() throws Exception {
		final PinSpecGroup buttons = createButtons();
		final PinSpecGroup verticalConnect = createVertical();
		final PinSpecGroup spi = createSPI();
		final PinSpecGroup arm1 = createArm1();
		final PinSpecGroup arm2 = createArm2();
		final PinSpecGroup arm3 = createArm3();
		final PinSpecGroup arm4 = createArm4();
		final FPGASpec fpga = new FPGASpec("MicroSemi", "ProAsic 3", "a3pn250-VQ100");
		final File file = new File("/Users/karstenbecker/Dropbox/PSHDL/Board/BoardOutline.svg");
		final String svg = Files.toString(file, Charsets.UTF_8);
		final SAXParserFactory factory = SAXParserFactory.newInstance();
		final SAXParser saxParser = factory.newSAXParser();
		final Multimap<String, String> res = LinkedHashMultimap.create();
		final DefaultHandler handler = new DefaultHandler() {
			Pattern p = Pattern.compile("(.*?)_\\d+_");

			@Override
			public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
				final String id = attributes.getValue("id");
				final Set<String> allow = Sets.newHashSet("SCL", "SDA", "TDO", "TMS", "TRST", "TDI");
				if (id != null) {
					if (id.startsWith("Pin") || id.startsWith("GND") || id.startsWith("VCC") || allow.contains(id)) {
						final Matcher matcher = p.matcher(id);
						if (matcher.find()) {
							res.put(matcher.group(1), id);
						} else {
							res.put(id, id);
						}
					} else {
						System.out.println("Ignoring:" + id);
					}
				}
			}
		};
		saxParser.parse(file, handler);
		final InteractiveMap map = new InteractiveMap(svg, res.asMap());
		final BoardSpecSettings pshdlBoard = new BoardSpecSettings("PSHDL Board", "Version 0.1 of the PSHDL Board", fpga, map, buttons, verticalConnect, spi, arm1, arm2, arm3,
				arm4);
		return pshdlBoard;
	}

	public static void main(String[] args) throws Exception {
		final BoardSpecSettings spec = generateBoardSpec();
		JSONHelper.getWriter().writeValue(new File("/Users/karstenbecker/Dropbox/PSHDL/boards/pshdlBoard.json"), spec);
		// generateExample(spec);
		final ObjectReader reader = JSONHelper.getReader(SynthesisSettings.class);
		final SynthesisSettings syn = reader.readValue(new File("/var/pshdl/5A186C450BB3539E/SynSettings.json"));
		final String string = syn.toString("clock", "reset", spec, new SynthesisSettings.PDCWriter());
		System.out.println(string);
	}

	public static void generateExample(BoardSpecSettings board) {
		final PinSpecGroup buttons = board.pinGroups.get(0);
		final PinSpecGroup spi = board.pinGroups.get(2);
		final PinSpecGroup arm1 = board.pinGroups.get(3);
		final PinSpecGroup arm2 = board.pinGroups.get(4);
		final PinSpecGroup arm3 = board.pinGroups.get(5);
		final PinSpecGroup arm4 = board.pinGroups.get(6);
		buttons.pins.get(3).assignedSignal = "reset_n";
		buttons.pins.get(2).assignedSignal = "button";
		int red = 0, green = 0, blue = 0;
		final int inArm = 0, outArm = 0;
		spi.pins.get(0).assignedSignal = "ss";
		spi.pins.get(1).assignedSignal = "miso";
		spi.pins.get(2).assignedSignal = "mosi";
		spi.pins.get(3).assignedSignal = "sclk";
		for (final PinSpec ps : arm1.pins) {
			if (ps.portName.toLowerCase().contains("red")) {
				ps.assignedSignal = "red[" + red++ + "]";
			}
			if (ps.portName.toLowerCase().contains("green")) {
				ps.assignedSignal = "green[" + green++ + "]";
			}
			if (ps.portName.toLowerCase().contains("blue")) {
				ps.assignedSignal = "blue[" + blue++ + "]";
			}
			// if (ps.portName.contains("Interconnect[0]")) {
			// ps.assignedSignal = "inArm[" + inArm++ + "]";
			// }
			// if (ps.portName.contains("Interconnect[1]")) {
			// ps.assignedSignal = "outArm[" + outArm++ + "]";
			// }
		}
		for (final PinSpec ps : arm2.pins) {
			if (ps.portName.toLowerCase().contains("red")) {
				ps.assignedSignal = "red[" + red++ + "]";
			}
			if (ps.portName.toLowerCase().contains("green")) {
				ps.assignedSignal = "green[" + green++ + "]";
			}
			if (ps.portName.toLowerCase().contains("blue")) {
				ps.assignedSignal = "blue[" + blue++ + "]";
			}
			// if (ps.portName.contains("Interconnect[0]")) {
			// ps.assignedSignal = "inArm[" + inArm++ + "]";
			// }
			// if (ps.portName.contains("Interconnect[1]")) {
			// ps.assignedSignal = "outArm[" + outArm++ + "]";
			// }
		}
		for (final PinSpec ps : arm3.pins) {
			if (ps.portName.toLowerCase().contains("red")) {
				ps.assignedSignal = "red[" + red++ + "]";
			}
			if (ps.portName.toLowerCase().contains("green")) {
				ps.assignedSignal = "green[" + green++ + "]";
			}
			if (ps.portName.toLowerCase().contains("blue")) {
				ps.assignedSignal = "blue[" + blue++ + "]";
			}
			// if (ps.portName.contains("Interconnect[0]")) {
			// ps.assignedSignal = "inArm[" + inArm++ + "]";
			// }
			// if (ps.portName.contains("Interconnect[1]")) {
			// ps.assignedSignal = "outArm[" + outArm++ + "]";
			// }
		}
		for (final PinSpec ps : arm4.pins) {
			if (ps.portName.toLowerCase().contains("red")) {
				ps.assignedSignal = "red[" + red++ + "]";
			}
			if (ps.portName.toLowerCase().contains("green")) {
				ps.assignedSignal = "green[" + green++ + "]";
			}
			if (ps.portName.toLowerCase().contains("blue")) {
				ps.assignedSignal = "blue[" + blue++ + "]";
			}
			// if (ps.portName.contains("Interconnect[0]")) {
			// ps.assignedSignal = "inArm[" + inArm++ + "]";
			// }
			// if (ps.portName.contains("Interconnect[1]")) {
			// ps.assignedSignal = "outArm[" + outArm++ + "]";
			// }
		}
	}

	private static PinSpecGroup createSPI() {
		final Map<String, String> attr = Maps.newHashMap();
		attr.put("fixed", "yes");
		attr.put("iostd", "LVTTL");
		final PinSpec ss = new PinSpec("ss", "43", "Chip select", attr, null, Polarity.active_low, HDLDirection.IN);
		final PinSpec miso = new PinSpec("miso", "96", "Serial output of the FPGA and the input for the Atmel", attr, null, null, HDLDirection.OUT);
		final PinSpec mosi = new PinSpec("mosi", "95", "Serial input of the FPGA and the output for the Atmel", attr, null, null, HDLDirection.IN);
		final PinSpec sclk = new PinSpec("sclk", "94", "Sata clock as generated by the Atmel", attr, null, null, HDLDirection.IN);
		return new PinSpecGroup("SPI bus", "These 4 lines are connected to the Atmel and can be used as SPI Bus. The FPGA acts as slave in this case", ss, miso, mosi, sclk);
	}

	private static PinSpecGroup createButtons() {
		final Map<String, String> attr = Maps.newHashMap();
		attr.put("fixed", "yes");
		attr.put("iostd", "LVTTL");

		final PinSpec clk = new PinSpec("Clock", "97", "This is the primary clock driven by PC4 of the Atmel", attr, new TimeSpec("32", "MHz"), null, HDLDirection.IN);
		clk.assignedSignal = "$clk";
		final HashMap<String, String> reset = new HashMap<>(attr);
		reset.put(PinSpec.INVERT, PinSpec.NO_VALUE);
		final PinSpec rstButton = new PinSpec("Reset", "98", "This is the inverted button S1", reset, null, Polarity.active_low, HDLDirection.IN);
		rstButton.assignedSignal = "$rst";
		final PinSpec oszilator = new PinSpec("oszilator", "93", "This is the DS1089L oszilator which is not populated by default", attr, null, null, HDLDirection.IN);
		final PinSpec button1 = new PinSpec("Button[0]", "98", "This is button S1", attr, null, Polarity.active_low, HDLDirection.IN);
		final PinSpec button2 = new PinSpec("Button[1]", "23", "This is button S2", attr, null, Polarity.active_low, HDLDirection.IN);
		return new PinSpecGroup("Buttons/clock", "There are two push buttons directly attached to the FPGA", clk, rstButton, oszilator, button1, button2);
	}

	private static PinSpecGroup createVertical() {
		final Map<String, String> attr = Maps.newHashMap();
		attr.put("fixed", "yes");
		attr.put("iostd", "LVTTL");

		final PinSpec upOne = new PinSpec("Vertical_interconnect_up[0]", "45", "The first interconnect pin upwards", attr, null, null, HDLDirection.INOUT);
		final PinSpec upTwo = new PinSpec("Vertical_interconnect_up[1]", "44", "The second interconnect pin upwards", attr, null, null, HDLDirection.INOUT);
		final PinSpec downOne = new PinSpec("Vertical_interconnect_down[0]", "3", "The first interconnect pin downwards", attr, null, null, HDLDirection.INOUT);
		final PinSpec downTwo = new PinSpec("Vertical_interconnect_down[1]", "2", "The second interconnect pin downwards", attr, null, null, HDLDirection.INOUT);
		return new PinSpecGroup("Vertical Connect", "Those connectors can be used to form a vertical interconnect", upOne, upTwo, downOne, downTwo);
	}

	private static PinSpecGroup createArm1() {
		final Map<String, String> attr = Maps.newHashMap();
		attr.put("fixed", "yes");
		attr.put("iostd", "LVTTL");

		final PinSpec red1 = new PinSpec("Arm1_Red[0]", "92", "Red part of the first RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec red2 = new PinSpec("Arm1_Red[1]", "86", "Red part of the second RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec red3 = new PinSpec("Arm1_Red[2]", "83", "Red part of the third RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec red4 = new PinSpec("Arm1_Red[3]", "80", "Red part of the forth RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec green1 = new PinSpec("Arm1_Green[0]", "90", "Green part of the first RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec green2 = new PinSpec("Arm1_Green[1]", "84", "Green part of the second RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec green3 = new PinSpec("Arm1_Green[2]", "81", "Green part of the third RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec green4 = new PinSpec("Arm1_Green[3]", "78", "Green part of the forth RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec blue1 = new PinSpec("Arm1_Blue[0]", "91", "Blue part of the first RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec blue2 = new PinSpec("Arm1_Blue[1]", "85", "Blue part of the second RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec blue3 = new PinSpec("Arm1_Blue[2]", "82", "Blue part of the third RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec blue4 = new PinSpec("Arm1_Blue[3]", "79", "Blue part of the forth RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec ii0 = new PinSpec("Arm1_Interconnect[0]", "77", "The first interconnect pin of arm1", attr, null, null, HDLDirection.INOUT);
		final PinSpec ii1 = new PinSpec("Arm1_Interconnect[1]", "76", "The second interconnect pin of arm1", attr, null, null, HDLDirection.INOUT);
		return new PinSpecGroup("LED Arm 1", "4 RGB LEDs and 2 interconnection IOs", red1, green1, blue1, red2, green2, blue2, red3, green3, blue3, red4, green4, blue4, ii0, ii1);
	}

	private static PinSpecGroup createArm2() {
		final Map<String, String> attr = Maps.newHashMap();
		attr.put("fixed", "yes");
		attr.put("iostd", "LVTTL");

		final PinSpec red1 = new PinSpec("Arm2_Red[0]", "73", "Red part of the first RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec red2 = new PinSpec("Arm2_Red[1]", "70", "Red part of the second RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec red3 = new PinSpec("Arm2_Red[2]", "64", "Red part of the third RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec red4 = new PinSpec("Arm2_Red[3]", "61", "Red part of the forth RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec green1 = new PinSpec("Arm2_Green[0]", "71", "Green part of the first RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec green2 = new PinSpec("Arm2_Green[1]", "65", "Green part of the second RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec green3 = new PinSpec("Arm2_Green[2]", "62", "Green part of the third RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec green4 = new PinSpec("Arm2_Green[3]", "59", "Green part of the forth RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec blue1 = new PinSpec("Arm2_Blue[0]", "72", "Blue part of the first RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec blue2 = new PinSpec("Arm2_Blue[1]", "69", "Blue part of the second RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec blue3 = new PinSpec("Arm2_Blue[2]", "63", "Blue part of the third RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec blue4 = new PinSpec("Arm2_Blue[3]", "60", "Blue part of the forth RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec ii0 = new PinSpec("Arm2_Interconnect[0]", "58", "The first interconnect pin of arm2", attr, null, null, HDLDirection.INOUT);
		final PinSpec ii1 = new PinSpec("Arm2_Interconnect[1]", "57", "The second interconnect pin of arm2", attr, null, null, HDLDirection.INOUT);
		return new PinSpecGroup("LED Arm 2", "4 RGB LEDs and 2 interconnection IOs", red1, green1, blue1, red2, green2, blue2, red3, green3, blue3, red4, green4, blue4, ii0, ii1);
	}

	private static PinSpecGroup createArm3() {
		final Map<String, String> attr = Maps.newHashMap();
		attr.put("fixed", "yes");
		attr.put("iostd", "LVTTL");

		final PinSpec red1 = new PinSpec("Arm3_Red[0]", "22", "Red part of the first RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec red2 = new PinSpec("Arm3_Red[1]", "19", "Red part of the second RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec red3 = new PinSpec("Arm3_Red[2]", "13", "Red part of the third RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec red4 = new PinSpec("Arm3_Red[3]", "8", "Red part of the forth RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec green1 = new PinSpec("Arm3_Green[0]", "20", "Green part of the first RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec green2 = new PinSpec("Arm3_Green[1]", "15", "Green part of the second RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec green3 = new PinSpec("Arm3_Green[2]", "10", "Green part of the third RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec green4 = new PinSpec("Arm3_Green[3]", "6", "Green part of the forth RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec blue1 = new PinSpec("Arm3_Blue[0]", "21", "Blue part of the first RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec blue2 = new PinSpec("Arm3_Blue[1]", "16", "Blue part of the second RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec blue3 = new PinSpec("Arm3_Blue[2]", "11", "Blue part of the third RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec blue4 = new PinSpec("Arm3_Blue[3]", "7", "Blue part of the forth RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec ii0 = new PinSpec("Arm3_Interconnect[0]", "5", "The first interconnect pin of arm3", attr, null, null, HDLDirection.INOUT);
		final PinSpec ii1 = new PinSpec("Arm3_Interconnect[1]", "4", "The second interconnect pin of arm3", attr, null, null, HDLDirection.INOUT);
		return new PinSpecGroup("LED Arm 3", "4 RGB LEDs and 2 interconnection IOs", red1, green1, blue1, red2, green2, blue2, red3, green3, blue3, red4, green4, blue4, ii0, ii1);
	}

	private static PinSpecGroup createArm4() {
		final Map<String, String> attr = Maps.newHashMap();
		attr.put("fixed", "yes");
		attr.put("iostd", "LVTTL");

		final PinSpec red1 = new PinSpec("Arm4_Red[0]", "42", "Red part of the first RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec red2 = new PinSpec("Arm4_Red[1]", "36", "Red part of the second RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec red3 = new PinSpec("Arm4_Red[2]", "33", "Red part of the third RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec red4 = new PinSpec("Arm4_Red[3]", "30", "Red part of the forth RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec green1 = new PinSpec("Arm4_Green[0]", "40", "Green part of the first RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec green2 = new PinSpec("Arm4_Green[1]", "34", "Green part of the second RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec green3 = new PinSpec("Arm4_Green[2]", "31", "Green part of the third RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec green4 = new PinSpec("Arm4_Green[3]", "28", "Green part of the forth RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec blue1 = new PinSpec("Arm4_Blue[0]", "41", "Blue part of the first RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec blue2 = new PinSpec("Arm4_Blue[1]", "35", "Blue part of the second RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec blue3 = new PinSpec("Arm4_Blue[2]", "32", "Blue part of the third RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec blue4 = new PinSpec("Arm4_Blue[3]", "29", "Blue part of the forth RGB LED", attr, null, Polarity.active_low, HDLDirection.OUT);
		final PinSpec ii0 = new PinSpec("Arm4_Interconnect[0]", "27", "The first interconnect pin of arm4", attr, null, null, HDLDirection.INOUT);
		final PinSpec ii1 = new PinSpec("Arm4_Interconnect[1]", "26", "The second interconnect pin of arm4", attr, null, null, HDLDirection.INOUT);
		return new PinSpecGroup("LED Arm 4", "4 RGB LEDs and 2 interconnection IOs", red1, green1, blue1, red2, green2, blue2, red3, green3, blue3, red4, green4, blue4, ii0, ii1);
	}
}
