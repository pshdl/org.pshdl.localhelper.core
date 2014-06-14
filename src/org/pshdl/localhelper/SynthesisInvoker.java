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
package org.pshdl.localhelper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pshdl.localhelper.WorkspaceHelper.IWorkspaceListener;
import org.pshdl.localhelper.WorkspaceHelper.MessageHandler;
import org.pshdl.model.HDLArgument;
import org.pshdl.model.HDLAssignment;
import org.pshdl.model.HDLExport;
import org.pshdl.model.HDLExpression;
import org.pshdl.model.HDLInterface;
import org.pshdl.model.HDLInterfaceInstantiation;
import org.pshdl.model.HDLInterfaceRef;
import org.pshdl.model.HDLLiteral;
import org.pshdl.model.HDLManip;
import org.pshdl.model.HDLManip.HDLManipType;
import org.pshdl.model.HDLObject;
import org.pshdl.model.HDLRange;
import org.pshdl.model.HDLStatement;
import org.pshdl.model.HDLUnit;
import org.pshdl.model.HDLVariable;
import org.pshdl.model.HDLVariableDeclaration;
import org.pshdl.model.HDLVariableDeclaration.HDLDirection;
import org.pshdl.model.HDLVariableRef;
import org.pshdl.model.extensions.FullNameExtension;
import org.pshdl.model.utils.HDLCore;
import org.pshdl.model.utils.HDLQualifiedName;
import org.pshdl.model.utils.HDLQuery;
import org.pshdl.rest.models.CompileInfo;
import org.pshdl.rest.models.FileRecord;
import org.pshdl.rest.models.Message;
import org.pshdl.rest.models.ProgressFeedback;
import org.pshdl.rest.models.ProgressFeedback.ProgressType;
import org.pshdl.rest.models.settings.BoardSpecSettings;
import org.pshdl.rest.models.settings.BoardSpecSettings.PinSpec;
import org.pshdl.rest.models.settings.SynthesisSettings;

import com.fasterxml.jackson.databind.ObjectReader;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

public class SynthesisInvoker implements MessageHandler<String> {

	private static Executor executor = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));

	public static final String SYNTHESIS_CREATOR = "Synthesis";

	public class SynJob implements Runnable, IProgressReporter {

		private static final String SRC_GEN_SYNTHESIS = "src-gen/synthesis";

		private final class VHDLFile implements FilenameFilter {
			@Override
			public boolean accept(File arg0, String arg1) {
				if (arg1.endsWith(".vhdl"))
					return true;
				if (arg1.endsWith(".vhd"))
					return true;
				return false;
			}
		}

		private final SynthesisSettings settings;
		private final File workspaceDir;
		private final String settingsFile;
		private final String workspaceID;

		public SynJob(SynthesisSettings settings, String settingsFile, File workspaceDir, String workspaceID) {
			this.settings = settings;
			this.settingsFile = settingsFile;
			this.workspaceDir = workspaceDir;
			this.workspaceID = workspaceID;
		}

		@Override
		public void run() {
			final File[] vhdlFiles = workspaceDir.listFiles(new VHDLFile());
			final ArrayList<File> files = Lists.newArrayList(vhdlFiles);
			final File srcGen = new File(workspaceDir, "src-gen");
			if (srcGen.exists()) {
				final File[] list = srcGen.listFiles(new VHDLFile());
				files.addAll(Arrays.asList(list));
			}
			try {
				final File synDir = new File(workspaceDir, SRC_GEN_SYNTHESIS);
				if (!synDir.exists()) {
					if (!synDir.mkdirs())
						throw new IllegalArgumentException("Failed to create directory:" + synDir);
				}
				final File boardFile = new File(workspaceDir, settings.board);
				final ObjectReader reader = JSONHelper.getReader(BoardSpecSettings.class);
				final BoardSpecSettings board = reader.readValue(boardFile);
				final ISynthesisTool synTool = toolMap.get(board.fpga.vendor.toLowerCase());
				if (synTool == null)
					throw new IllegalArgumentException("Did not find tool for vendor:" + board.fpga.vendor + ". Only know tools for " + toolMap.keySet());
				final String topModule = settings.topModule;
				final CompileInfo result = synTool.runSynthesis(topModule, getWrapperName(topModule), files, synDir, board, settings, this, null);
				if (result != null) {
					connectionHelper.postMessage(Message.COMP_SYNTHESIS, "CompileInfo[]", new CompileInfo[] { result });
				}
			} catch (final Throwable e) {
				e.printStackTrace();
			}
		}

		@Override
		public FileRecord reportFile(final CompileInfo info, final File srrLog, final String fileName) throws IOException {
			final String relPath = SRC_GEN_SYNTHESIS + fileName;
			final FileRecord fileRecord = new FileRecord(srrLog, workspaceDir, workspaceID);
			fileRecord.updateURI(workspaceID, relPath);
			info.getFiles().add(fileRecord);
			connectionHelper.uploadDerivedFile(srrLog, workspaceID, relPath, info, settingsFile);
			return fileRecord;
		}

		@Override
		public void reportProgress(ProgressType type, Double progress, String message) throws IOException {
			sendMessage(type, progress, message);
		}

	}

	public static interface IProgressReporter {
		/**
		 *
		 * @param type
		 *            the {@link ProgressType} of this progress
		 * @param progress
		 *            either <code>null</code> in case of errors, or a number
		 *            between (0..1)
		 * @param message
		 *            a human readable string that give the user an idea of what
		 *            is going on, or a JSON object
		 * @throws IOException
		 */
		void reportProgress(ProgressType type, Double progress, String message) throws IOException;

		/**
		 * This method can be used to create a file record that is used to
		 * communicate with the server
		 *
		 * @param info
		 *            the info about the synthesis
		 * @param datFile
		 *            the generated file
		 * @param datRelPath
		 *            the relative path of the file within the workspace
		 * @return a FileRecord that contains information about the file
		 * @throws IOException
		 */
		FileRecord reportFile(CompileInfo info, File datFile, String datRelPath) throws IOException;
	}

	public static Process runProcess(final File synDir, final ProcessBuilder processBuilder, int timeOutMinutes, String stage, final double progress,
			final IProgressReporter reporter) throws IOException, InterruptedException {
		processBuilder.redirectErrorStream(true);
		processBuilder.directory(synDir);
		final Process process = processBuilder.start();
		final InputStream is = process.getInputStream();
		final StringBuilder sb = new StringBuilder();
		new Thread(new Runnable() {
			@Override
			public void run() {
				final BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
				String line = null;
				double progressCounter = progress;
				final String absolutePath = synDir.getAbsolutePath();
				try {
					while ((line = reader.readLine()) != null) {
						line = line.replace(absolutePath, "");
						sb.append(line).append('\n');
						if (line.startsWith("#!>")) {
							reporter.reportProgress(ProgressType.progress, progressCounter, line.substring(3));
							progressCounter += 0.15;
						}
					}
				} catch (final IOException e) {
				}
			}
		}, "OutputLogger").start();
		if (!waitOrTerminate(process, timeOutMinutes)) {
			reporter.reportProgress(ProgressType.error, null, "Consumed more than " + timeOutMinutes + " minutes for " + stage);
		}
		if (!sb.toString().trim().isEmpty()) {
			reporter.reportProgress(ProgressType.output, null, sb.toString());
		}
		return process;
	}

	public void sendMessage(ProgressType type, Double progress, String message) throws IOException {
		final ProgressFeedback synProgress = new ProgressFeedback(type, progress, System.currentTimeMillis(), message);
		connectionHelper.postMessage(Message.SYNTHESIS_PROGRESS, "ProgressFeedback", synProgress);
		System.out.println("SynthesisInvoker.SynJob.sendMessage()" + type + " " + message);
	}

	public static boolean waitOrTerminate(final Process synProcess, int waitTime) throws InterruptedException {
		boolean done = false;
		for (int i = 0; i < (60 * waitTime); i++) {
			try {
				synProcess.exitValue();
				done = true;
				break;
			} catch (final Exception e) {
			}
			Thread.sleep(1000);
		}
		if (!done) {
			synProcess.destroy();
		}
		return done;
	}

	public static HDLUnit createSynthesisContainer(final SynthesisSettings setting, final HDLUnit unit) {
		final HDLVariable hifVar = new HDLVariable().setName("wrapper");
		final HDLQualifiedName wrapperRef = hifVar.asRef();
		final HDLInterface unitIF = unit.asInterface();
		final HDLQualifiedName fqn = FullNameExtension.fullNameOf(unit);
		HDLInterfaceInstantiation hii = new HDLInterfaceInstantiation().setHIf(fqn).setVar(hifVar);
		final Map<String, String> overrideParameters = setting.overrideParameters;
		if (overrideParameters != null) {
			for (final Entry<String, String> e : overrideParameters.entrySet()) {
				final HDLExpression expression = getExpression(e.getKey(), e.getValue());
				if (expression != null) {
					hii = hii.addArguments(new HDLArgument().setName(e.getKey()).setExpression(expression));
				}
			}
		}
		final List<HDLStatement> stmnts = Lists.newArrayList();
		stmnts.add(hii);
		final Set<String> declaredSignals = Sets.newHashSet();
		for (final PinSpec ps : setting.overrides) {
			switch (ps.pinLocation) {
			case PinSpec.SIG_NONE:
			case PinSpec.SIG_OPEN:
				break;
			case PinSpec.SIG_ALLONE: {
				final HDLInterfaceRef hir = createInterfaceRef(new HDLInterfaceRef().setHIf(wrapperRef), ps.assignedSignal, wrapperRef);
				stmnts.add(new HDLAssignment().setLeft(hir).setRight(HDLLiteral.get(1)));
				break;
			}
			case PinSpec.SIG_ALLZERO: {
				final HDLInterfaceRef hir = createInterfaceRef(new HDLInterfaceRef().setHIf(wrapperRef), ps.assignedSignal, wrapperRef);
				stmnts.add(new HDLAssignment().setLeft(hir).setRight(HDLLiteral.get(0)));
				break;
			}
			default:
				final HDLVariableRef var = createInterfaceRef(new HDLVariableRef(), ps.assignedSignal, wrapperRef);
				final String varName = var.getVarRefName().getLastSegment();
				if (!declaredSignals.contains(varName)) {
					declaredSignals.add(varName);
					final Collection<HDLVariable> vars = HDLQuery.select(HDLVariable.class).from(unitIF).where(HDLVariable.fName).isEqualTo(varName).getAll();
					for (final HDLVariable hdlVariable : vars) {
						if (hdlVariable.getContainer() instanceof HDLVariableDeclaration) {
							HDLVariableDeclaration declaration = (HDLVariableDeclaration) hdlVariable.getContainer();
							if (declaration.getDirection() != HDLDirection.INOUT) {
								declaration = declaration.setVariables(HDLObject.asList(hdlVariable.setDefaultValue(null)));
								stmnts.add(declaration);
							}
							break;
						}
					}
				}
				final HDLInterfaceRef hir = createInterfaceRef(new HDLInterfaceRef().setHIf(wrapperRef), ps.assignedSignal, wrapperRef);
				switch (ps.direction) {
				case IN:
					stmnts.add(new HDLAssignment().setLeft(hir).setRight(doNeg(var, ps)));
					break;
				case OUT:
					stmnts.add(new HDLAssignment().setLeft(var).setRight(doNeg(hir, ps)));
					break;
				case INOUT:
					stmnts.add(new HDLExport().setExportRef(hir));
					break;
				default:
				}
				break;
			}
		}
		final HDLUnit container = new HDLUnit().setSimulation(false).setName(getWrapperName(setting.topModule)).setStatements(stmnts);
		// System.out.println(container);
		return container;
	}

	private static HDLExpression doNeg(HDLVariableRef var, PinSpec ps) {
		if ((ps.attributes != null) && ps.attributes.containsKey(PinSpec.INVERT))
			return new HDLManip().setType(HDLManipType.BIT_NEG).setTarget(var);
		return var;
	}

	private static final Pattern arrays = Pattern.compile("\\[(.*?)\\]");
	private static final Pattern bit = Pattern.compile("\\{(.*?)\\}");

	@SuppressWarnings("unchecked")
	private static <T extends HDLVariableRef> T createInterfaceRef(T hir, String assignedSignal, HDLQualifiedName wrapperRef) {
		final Matcher arrayM = arrays.matcher(assignedSignal);
		int lastIndex = assignedSignal.length();
		while (arrayM.find()) {
			if (arrayM.start() < lastIndex) {
				lastIndex = arrayM.start();
			}
			hir = (T) hir.addArray(getExpression(null, arrayM.group(1)));
		}
		final Matcher bitM = bit.matcher(assignedSignal);
		if (bitM.find()) {
			if (bitM.start() < lastIndex) {
				lastIndex = bitM.start();
			}
			hir = (T) hir.setBits(HDLObject.asList(new HDLRange().setTo(getExpression(null, bitM.group(1)))));
		}
		hir = (T) hir.setVar(new HDLQualifiedName(assignedSignal.substring(0, lastIndex)));
		return hir;
	}

	public static HDLExpression getExpression(final String paramName, final String value) {
		HDLExpression exp = null;
		if (value.charAt(0) == '"') {
			exp = HDLLiteral.getString(value);
		} else if (value.equalsIgnoreCase("true")) {
			exp = HDLLiteral.getTrue();
		} else if (value.equalsIgnoreCase("false")) {
			exp = HDLLiteral.getFalse();
		} else if (value.matches("[_\\d]+")) {
			try {
				final BigInteger bigVal = new BigInteger(value.trim());
				exp = HDLLiteral.get(bigVal);
			} catch (final Exception e1) {
				throw new IllegalArgumentException("The value '" + value + "' of key '" + paramName + "' is not valid:" + e1.getMessage());
			}
		} else if (value.matches("\\D[\\.\\w]*")) {
			exp = new HDLVariableRef().setVar(new HDLQualifiedName(value));
		} else
			throw new IllegalArgumentException("The value '" + value + "' of key '" + paramName + "' is not valid");
		return exp;
	}

	private final ConnectionHelper connectionHelper;
	private static final Map<String, ISynthesisTool> toolMap = Maps.newHashMap();
	static {
		final Collection<ISynthesisTool> tools = HDLCore.getAllImplementations(ISynthesisTool.class);
		for (final ISynthesisTool tool : tools) {
			for (final String vendor : tool.getSupportedFPGAVendors()) {
				toolMap.put(vendor.toLowerCase(), tool);
			}
		}
	}

	public SynthesisInvoker(ConnectionHelper ch) {
		this.connectionHelper = ch;
	}

	@Override
	public void handle(Message<String> msg, IWorkspaceListener listener, File workspaceDir, String workspaceID) throws Exception {
		final String path = WorkspaceHelper.getContent(msg, String.class);
		final ObjectReader reader = JSONHelper.getReader(SynthesisSettings.class);
		final SynthesisSettings contents = reader.readValue(new File(workspaceDir, path));
		executor.execute(new SynJob(contents, path, workspaceDir, workspaceID));
	}

	public static String getWrapperName(final String topModule) {
		return ("Synthesis" + topModule + "Wrapper").replace('.', '_');
	}

}
