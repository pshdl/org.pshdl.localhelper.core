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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.cli.CommandLine;
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
import org.pshdl.model.parser.PSHDLParser;
import org.pshdl.model.utils.HDLCore;
import org.pshdl.model.utils.HDLQualifiedName;
import org.pshdl.model.utils.HDLQuery;
import org.pshdl.model.validation.Problem;
import org.pshdl.rest.models.CompileInfo;
import org.pshdl.rest.models.FileInfo;
import org.pshdl.rest.models.FileRecord;
import org.pshdl.rest.models.Message;
import org.pshdl.rest.models.ProgressFeedback;
import org.pshdl.rest.models.ProgressFeedback.ProgressType;
import org.pshdl.rest.models.RepoInfo;
import org.pshdl.rest.models.settings.BoardSpecSettings;
import org.pshdl.rest.models.settings.BoardSpecSettings.PinSpec;
import org.pshdl.rest.models.settings.SynthesisSettings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

public class SynthesisInvoker implements MessageHandler<String> {

	private static Executor executor = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));

	public static final String SYNTHESIS_CREATOR = "Synthesis";

	public class SynJob implements Runnable, IProgressReporter {

		private static final String SRC_GEN_SYNTHESIS = "src-gen/synthesis/";

		private final SynthesisSettings settings;
		private final File workspaceDir;
		private final String settingsFile;
		private final String workspaceID;
		private final RepoInfo repo;

		public SynJob(SynthesisSettings settings, String settingsFile, File workspaceDir, String workspaceID, RepoInfo repo) {
			this.settings = settings;
			this.settingsFile = settingsFile;
			this.workspaceDir = workspaceDir;
			this.workspaceID = workspaceID;
			this.repo = repo;
		}

		@Override
		public void run() {
			final List<String> vhdlCompilerArgs = Lists.newArrayList();
			final File vhdlOutputDir = new File(workspaceDir, "src-gen");
			vhdlCompilerArgs.add("-o");
			vhdlCompilerArgs.add(vhdlOutputDir.getAbsolutePath());
			for (final FileInfo fileInfo : repo.getFiles()) {
				vhdlCompilerArgs.add(new File(workspaceDir, fileInfo.record.relPath).getAbsolutePath());
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
				final CommandLine cli = new SynthesisOutputProvider().getUsage().parse(vhdlCompilerArgs.toArray(new String[vhdlCompilerArgs.size()]));
				SynthesisOutputProvider.runSynthesis(cli, settings, board, board.fpga.vendor.toLowerCase(), vhdlOutputDir, synDir, this);
			} catch (final Throwable e) {
				e.printStackTrace();
				try {
					sendMessage(ProgressType.error, null, "Exception occured: " + e.getMessage());
				} catch (final IOException e1) {
					e1.printStackTrace();
				}
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

		@Override
		public void reportResult(CompileInfo compileInfo) throws IOException {
			final ObjectWriter writer = JSONHelper.getWriter();
			connectionHelper.postMessage(Message.COMP_SYNTHESIS, "CompileInfo[]", writer.writeValueAsString(new CompileInfo[] { compileInfo }));
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

		/**
		 * When the synthesis finishes, the compileInfo is reported
		 *
		 * @param compileInfo
		 * @throws IOException
		 */
		void reportResult(CompileInfo compileInfo) throws IOException;
	}

	/**
	 * Run a process and report progress. Lines that start with #!&gt; are
	 * directly reported to the reporter and progress is incremented by
	 * incProgress
	 *
	 * @param workingDir
	 *            the working directory in which the process will be running
	 * @param processBuilder
	 *            the process to run
	 * @param timeOutMinutes
	 *            the timeout after which the process will be killed
	 * @param stage
	 *            a human readable short description of what is done in this
	 *            process ('synthesis', 'implementation'...)
	 * @param progress
	 *            the base progress to which the incprogress will be added upon
	 *            each output line
	 * @param incProgress
	 *            the amount by which the progress is incremented on each output
	 *            line
	 * @param reporter
	 *            the reporter to which progress, as well as console output is
	 *            reported
	 * @return the process that was either terminated or is terminated
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public static Process runProcess(final File workingDir, final ProcessBuilder processBuilder, int timeOutMinutes, String stage, final double progress, final double incProgress,
			final IProgressReporter reporter) throws IOException, InterruptedException {
		processBuilder.redirectErrorStream(true);
		processBuilder.directory(workingDir);
		final Process process = processBuilder.start();
		final InputStream is = process.getInputStream();
		final StringBuilder sb = new StringBuilder();
		new Thread(new Runnable() {
			@Override
			public void run() {
				try (final BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
					String line = null;
					double progressCounter = progress;
					final String absolutePath = workingDir.getAbsolutePath();
					while ((line = reader.readLine()) != null) {
						line = line.replace(absolutePath, "");
						sb.append(line).append('\n');
						if (line.startsWith("#!>")) {
							reporter.reportProgress(ProgressType.progress, progressCounter, line.substring(3));
							progressCounter += incProgress;
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

	public static void reportFile(IProgressReporter reporter, final CompileInfo info, final ObjectWriter writer, final File srrLog, final String implRelPath)
			throws IOException, JsonProcessingException {
		final FileRecord fileRecord = reporter.reportFile(info, srrLog, implRelPath);
		reporter.reportProgress(ProgressType.report, null, writer.writeValueAsString(fileRecord));
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
				final HDLExpression expression = extractExpression(e.getKey(), e.getValue());
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
					stmnts.add(new HDLAssignment().setLeft(hir).setRight(invertVarRefIfSpecified(var, ps)));
					break;
				case OUT:
					stmnts.add(new HDLAssignment().setLeft(var).setRight(invertVarRefIfSpecified(hir, ps)));
					break;
				case INOUT:
					stmnts.add(new HDLExport().setHIf(hir.getHIfRefName()).setVar(hir.getVarRefName()));
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

	private static HDLExpression invertVarRefIfSpecified(HDLVariableRef var, PinSpec ps) {
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
			hir = (T) hir.addArray(extractExpression(null, arrayM.group(1)));
		}
		final Matcher bitM = bit.matcher(assignedSignal);
		if (bitM.find()) {
			if (bitM.start() < lastIndex) {
				lastIndex = bitM.start();
			}
			hir = (T) hir.setBits(HDLObject.asList(new HDLRange().setTo(extractExpression(null, bitM.group(1)))));
		}
		hir = (T) hir.setVar(new HDLQualifiedName(assignedSignal.substring(0, lastIndex)));
		return hir;
	}

	public static HDLExpression extractExpression(final String paramName, final String value) {
		final HashSet<Problem> problems = Sets.newHashSet();
		final HDLExpression hdlExpression = PSHDLParser.parseExpressionString(value, problems);
		if (!problems.isEmpty())
			throw new IllegalArgumentException("The value '" + value + "' of key '" + paramName + "' is not valid:" + problems.toString());
		return hdlExpression;
	}

	private final ConnectionHelper connectionHelper;
	private static final Map<String, ISynthesisTool> toolMap = Maps.newLinkedHashMap();

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
	public void handle(Message<String> msg, IWorkspaceListener listener, File workspaceDir, String workspaceID, RepoInfo info) throws Exception {
		final String path = WorkspaceHelper.getContent(msg, String.class);
		final ObjectReader reader = JSONHelper.getReader(SynthesisSettings.class);
		final SynthesisSettings contents = reader.readValue(new File(workspaceDir, path));
		executor.execute(new SynJob(contents, path, workspaceDir, workspaceID, info));
	}

	/**
	 * Returns the name of the wrapped top module
	 *
	 * @param topModule
	 * @return the name of the wrapped top module
	 */
	public static String getWrapperName(final String topModule) {
		return ("Synthesis" + topModule + "Wrapper").replace('.', '_');
	}

}
