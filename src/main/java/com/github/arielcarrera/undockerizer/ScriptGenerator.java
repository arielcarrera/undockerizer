package com.github.arielcarrera.undockerizer;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.github.arielcarrera.undockerizer.managers.AttachmentManager;
import com.github.arielcarrera.undockerizer.managers.ContentManager;
import com.github.arielcarrera.undockerizer.model.image.ConfigFile;
import com.github.arielcarrera.undockerizer.model.image.Manifest;
import com.github.arielcarrera.undockerizer.model.image.config.History;
import com.github.arielcarrera.undockerizer.utils.StringUtil;
import com.github.arielcarrera.undockerizer.writer.Writer;

import lombok.AllArgsConstructor;
import picocli.CommandLine.Help.Ansi;

/**
 * Script Generator
 * 
 * @author Ariel Carrera
 *
 */
@AllArgsConstructor
public class ScriptGenerator {

	private static final String REGEX_NO_OP = "(([\\w\\/\\\\.]*\\s*-c\\s)#\\(nop\\))\\s";

	private static final String REGEX_ENV = "([\\w_-]*)(?:=(.*))?";
	
	private static final String REGEX_BUILD_SECRET = "(\\/run\\/secrets\\/[\\w.\\\\\\/_\\-]*)";
	
	private boolean verbose;
	
	private boolean archive;
	
	private String shellPathStr;
	
	private Set<Path> resourcesToArchive;
	
	private String lastCommandSentence, lastEntryPointSentence;
	
	private boolean buildKitDetected = false;
	
	private Set<String> buildSecrets = new HashSet<String>();
	
	public ScriptGenerator(boolean verbose, boolean archive, String shellPathStr, Set<Path> resourcesToArchive) {
		super();
		this.verbose = verbose;
		this.archive = archive;
		this.shellPathStr = shellPathStr;
		this.resourcesToArchive = resourcesToArchive;
	}
	
	
	public void generateScript(Manifest manifest, ConfigFile cfg, Writer w) throws IOException {
		AttachmentManager attachManager = new AttachmentManager(manifest, cfg);
		List<History> hist = cfg.getHistory();
		boolean firstNoOpFound = false;
		String noOpsPrefix = null, instructionprefix = null;
		Pattern noOpPattern = Pattern.compile(REGEX_NO_OP);
		Pattern envPattern = Pattern.compile(REGEX_ENV);
		
		//check first if buildkit is enabled and a docker secret is mounted
		List<History> buildKit = hist.stream().filter(h -> (h.getComment() != null && h.getComment().startsWith("buildkit")) || 
				(h.getCreatedBy() != null && h.getCreatedBy().endsWith("# buildkit"))).collect(Collectors.toList());
		if (buildKit.isEmpty()) {
			System.out.println(Ansi.AUTO.string("@|bold,green,underline Buildkit mode not found|@"));
		} else {
			buildKitDetected = true;
			System.out.println(Ansi.AUTO.string("@|bold,yellow,underline Buildkit mode detected|@"));
			Pattern buildSecretPattern = Pattern.compile(REGEX_BUILD_SECRET);
			for (History h : buildKit) {
				String line = h.getCreatedBy();
				if (line != null) {
					Matcher matcher = buildSecretPattern.matcher(line);
					while (matcher.find()) {
						String path = matcher.group(1);
						System.out.println(Ansi.AUTO.string("@|green Docker build Secret detected: " +  path + "|@"));
						buildSecrets.add(path);
					}
				}
			}
		}
		if (buildKitDetected) {
			if (buildSecrets.isEmpty()) {
				w.writeMessage("WARN: The image could be generated with docker build secrets. Default paths for Docker build Secrets where not found. Note that if the files with the secrets used during the image build in the filesystem do not exist, the sript will fail.");
			} else {
				w.writeMessage("WARN: The image could be generated with docker build secrets.");
				for (String path : buildSecrets) {			
					w.writeMessage("Docker build secret detected: " +  path + " -> make sure you have the file in the detailed location");
					w.writeFileExists(path, "secret file '" +  path + "' not found!");
				}
			}
		}
		
		for (Iterator<History> iterator = hist.iterator(); iterator.hasNext();) {
			History history = iterator.next();
			if (verbose) System.out.println("Processing line: " +  history.getCreatedBy());
			
			String line = history.getCreatedBy();
			if (line == null) {
				if (firstNoOpFound) System.out.println("WARN: layer without creation data");
			} else {
				//check first command
				if (!firstNoOpFound) {
					if (history.isEmptyLayer()) {
						Matcher matcher = noOpPattern.matcher(line);
						if (matcher.find()) {
							firstNoOpFound = true;
							noOpsPrefix = matcher.group(1);
							instructionprefix = matcher.group(2);
							if (verbose) System.out.println("--> First command found. noOpsPrefix: " +  noOpsPrefix + ", instructionPrefix: " + instructionprefix);
						}
					} else {
						if (verbose) System.out.println("--> Skipped. First command not found. Line: " +  line);
					}
				} else {
					if (history.isEmptyLayer()) {
						// docker instructions
						if (line.startsWith(noOpsPrefix)) {
							// remove prefix and empty spaces at beginning
							String sentence = StringUtil.lTrim(line.substring(noOpsPrefix.length()));
							processSentence(w, envPattern, sentence, attachManager, line, cfg);
						} else {
							processSentence(w, envPattern, line, attachManager, line, cfg);
						}
					} else {
						// shell commands
						if (line.startsWith(noOpsPrefix)) { // /bin/sh -c
							// remove prefix and empty spaces at beginning
							String sentence = StringUtil.lTrim(line.substring(noOpsPrefix.length()));
							processSentence(w, envPattern, sentence, attachManager, line, cfg);
						} else if (line.startsWith(instructionprefix)) { // /bin/sh -c
							String value = StringUtil.lTrim(line.substring(instructionprefix.length()));
							w.writeCommand(value);
							if (verbose) System.out.println("--> Command line added: " + line);
						} else if (line.startsWith("|") && line.length() > 1) { // |N
							processWithArgs(w, line);
						} else {
							processSentence(w, envPattern, line, attachManager, line, cfg);
						}
					}
				}
			}
		}
	}

	private void processWithArgs(Writer w, String line) throws IOException {
		String value = null;
		int i = 1;
		for (; i < line.length(); i++) {
			char charAt = line.charAt(i);
			if (!Character.isDigit(charAt)){
				value = line.substring(i);
				break;
			}
		}
		if (value != null) {
			value = StringUtil.lTrim(value);
			writeCommandLineWithReplacements(w, value, line.substring(1, i));
			if (verbose) System.out.println("--> Command line added: " + value);
		} else {
			System.err.println("Error processing command line (skiped): " + line);
		}
	}

	private void processSentence(Writer w, Pattern envPattern, String sentence, AttachmentManager attachManager, String line, ConfigFile cfg) throws IOException {
		if (sentence.startsWith("LABEL ")) {
			String value = sentence.substring(5);
			w.writeComment(value);
			if (verbose) System.out.println("--> Label added: " + value);
		} else if (sentence.startsWith("MAINTAINER ")) {
			w.writeComment(sentence);
			if (verbose) System.out.println("--> Maintainer added: " + sentence);
		} else if (sentence.startsWith("ENV ")) {
			Matcher matcher = envPattern.matcher(sentence.substring(4));
			if (matcher.find()) {
				String key = matcher.group(1);
				String value;
				if (matcher.groupCount() > 1) {
					value = matcher.group(2);
				} else {
					value = "";
				}
				w.writeEnvVar(key, value);
				if (verbose) System.out.println("--> Environment variable added. key: " + key + ", value: " + value);
			} else {
				System.err.println("--> Error parsing ENV: not found");
			}
		} else if (sentence.startsWith("ARG ")) {
			Matcher matcher = envPattern.matcher(sentence.substring(4));
			if (matcher.find()) {
				String key = matcher.group(1);
				String value;
				if (matcher.groupCount() > 1) {
					value = matcher.group(2);
				} else {
					value = "";
				}
				if (value != null) {										
					w.writeVar(key, value);
				} else {
					w.writeComment("ARG var without default value: " +  key);
				}
				if (verbose) System.out.println("-->Local variable added. key: " + key + ", value: " + value);
			} else {
				System.err.println("--> Error parsing ARG: not found");
			}
		} else if (sentence.startsWith("USER ")) {
			String value = sentence.substring(5);
			w.writeChangeUser(value);
			if (verbose) System.out.println("--> Set user added: " + value);
		} else if (sentence.startsWith("WORKDIR ")) {
			String value = sentence.substring(8);
			w.writeCommand("mkdir -p " + value + " & cd " + value);
			if (verbose) System.out.println("--> Set workdir added: " + value);
		} else if (sentence.startsWith("EXPOSE ")) {
			String value = sentence.substring(7);
			w.writeComment("Expose Ports: " + value);
			if (verbose) System.out.println("--> Comment: Expose ports " + value);
		} else if (sentence.startsWith("CMD ")) {
			String value = sentence.trim();
			if (value.charAt(4) == '[' && value.charAt(5) == '"' && value.endsWith("\"]")) {
				value = value.substring(6, value.length() - 2); //remove also first " and last "
				StringTokenizer tokenizer = new StringTokenizer(value, "\" \"");
				StringBuilder builder = new StringBuilder();
				boolean appendSpace = false;
				while (tokenizer.hasMoreElements()) {
					String object = (String) tokenizer.nextElement();
					if (appendSpace) builder.append(" ");
					builder.append(object);
					appendSpace = true;
				}
				lastCommandSentence = builder.toString();
				if (verbose) System.out.println("--> CMD line saved: " + lastCommandSentence);
			} else {
				lastCommandSentence = value.substring(4); 
				if (verbose) System.out.println("--> CMD line saved: " + lastCommandSentence);
			}
		}  else if (sentence.startsWith("ENTRYPOINT ")) {
			String value = sentence.trim();
			if (value.charAt(11) == '[' && value.charAt(12) == '"' && value.endsWith("\"]")) {
				value = value.substring(13, value.length() - 2); //remove also first " and last "
				StringTokenizer tokenizer = new StringTokenizer(value, "\" \"");
				StringBuilder builder = new StringBuilder();
				boolean appendSpace = false;
				while (tokenizer.hasMoreElements()) {
					String object = (String) tokenizer.nextElement();
					if (appendSpace) builder.append(" ");
					builder.append(object);
					appendSpace = true;
				}
				lastEntryPointSentence = builder.toString();
				if (verbose) System.out.println("--> ENTRYPOINT line saved: " + lastEntryPointSentence);
			} else {
				lastEntryPointSentence = value.substring(4); 
				if (verbose) System.out.println("--> ENTRYPOINT line saved: " + lastEntryPointSentence);
			}
		} else	if (sentence.startsWith("ADD ")) {
			String value = sentence.substring(4);
			processAdd(w, value, attachManager.getAttachmentPath(line), cfg.getContainer());
		} else if (sentence.startsWith("COPY ")) {
			String value = sentence.substring(5);
			processAdd(w, value, attachManager.getAttachmentPath(line), cfg.getContainer());
		} else if (line.startsWith("RUN ")) {
			String value = line.substring(4);
			processWithArgs(w, value);
		} else {
			w.writeComment(sentence);
			if (verbose) System.err.println("--> WARN: Operation not supported (comment added): " + sentence);
		}
	}
	
	private void processAdd(Writer w, String value, String filePath, String container) throws IOException {
		String filename = null, target = null, user = null, group = null;
		int indexOfIn = value.indexOf(" ");
		if (indexOfIn < 0) {
			System.err.println("Error processing ADD instruction (parsing in clause).");
		} else 	{
			if (value.startsWith("--chown=")) {
				value = value.substring(8, indexOfIn);
				int indexOfColon = value.indexOf(":");
				if (indexOfColon < 0) {
					System.err.println("Error processing ADD instruction (parsing user).");
				} else {
					int indexOfColon2 = value.indexOf(":", indexOfColon + 1);
					if (indexOfColon2 < 0) {
						// (no group defined) userfile:filename
						user = value.substring(0, indexOfColon - 4);
						filename = value.substring(indexOfColon + 1);
					} else {
						// user:groupfile:filename
						user = value.substring(0, indexOfColon);
						group = value.substring(indexOfColon + 1, indexOfColon2 - 4);
						filename = value.substring(indexOfColon2 + 1);
					}
				}
			} else {
				if (value.startsWith("file:")) {
					filename = value.substring(5, indexOfIn);
				} else if (value.startsWith("dir:")) {
					filename = value.substring(4, indexOfIn);
				} else {
					filename = value.substring(0, indexOfIn);
				}
			}
			if (value.startsWith(" in ", indexOfIn)) {
				target = value.substring(indexOfIn + 4);
			} else {
				//  "in" clause not found. format like: layers.conf /opt/url # buildkit
				int indexOfComment = value.indexOf('#', indexOfIn + 1);
				if (indexOfComment < 0) {
					target = value.substring(indexOfIn + 1);
				} else {					
					target = value.substring(indexOfIn + 1, indexOfComment);
				}
			}
			if (filename != null && target != null) {
				String tempFileName = container + "/" + filePath;
				String contentFileName = ContentManager.getContentFolderName(container) + "/" + filePath;
				if (archive) resourcesToArchive.add(Paths.get(contentFileName));
				writeAdd(w, tempFileName, contentFileName, target, user, group);
				if (verbose) System.out.println("--> Files added: " + value);
			}
		}
	}

	private void writeAdd(Writer w, String tempFileName, String contentFileName, String target, String user, String group) throws IOException {
		String tmpDir = "$UNDOCKERIZER_WORKDIR/" + tempFileName;
		String sourceDir = "$UNDOCKERIZER_WORKDIR/" + contentFileName;
		w.writeCommand("mkdir -p " + tmpDir + " && tar -xvf " + sourceDir + " -C " + tmpDir);
		if (user != null) {
			if (group != null) {
				w.writeCommand("chown -R " + user + ":" + group + " " + tmpDir + "/");
			} else {
				w.writeCommand("chown -R " + user + " " + tmpDir + "/");
			}
		}
		w.writeCommand("cp -r " + tmpDir + "/* / && rm -rf " + tmpDir);
	}
	
	private void writeCommandLineWithReplacements(Writer w, String value, String numberOfParams) throws IOException {
		int number = Integer.parseInt(numberOfParams);
		int ocurrences = 0;
		int pos = 0;
		while (ocurrences <= number) {
			int indexOf = value.indexOf('=', pos);
			if (indexOf > -1) {
				ocurrences++;
			} else {
				throw new RuntimeException("Error processing command line with arguments.");
			}
			pos++;
		}
		int indexOf = value.indexOf(" -c ", pos);
		if (indexOf < 0) throw new RuntimeException("Error processing command line with arguments (-c parameter not found)");
		String preParam = value.substring(0, indexOf);
		int lastIndexOf = preParam.lastIndexOf(shellPathStr);
		if (lastIndexOf < 0) throw new RuntimeException("Error processing command line with arguments (shell not found)");
		w.writeCommand(value.substring(indexOf + 4), value.substring(0, lastIndexOf)); //StringUtil.escapeVars(value.substring(0, lastIndexOf)));
	}

}