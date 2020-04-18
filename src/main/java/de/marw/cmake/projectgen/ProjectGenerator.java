/*******************************************************************************
 * Copyright (c) 2020 Martin Weber.
 *
 * Content is provided to you under the terms and conditions of the Eclipse Public License Version 2.0 "EPL".
 * A copy of the EPL is available at http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/

package de.marw.cmake.projectgen;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Generates a project to be build using cmake with lots of source files.
 *
 * @author Martin Weber
 */
public class ProjectGenerator {
  private static final int DEFAULT_NumCmds = 1000;
  private static final int DEFAULT_NumCommonDefsPerCmd = 5;
  private static final int DEFAULT_NumCommonInclpathsPerCmdDef = 7;
  private static final int DEFAULT_PercentUniqueCmd = 15;
  private static final int DEFAULT_NumUniqueDefsPerCmd = 5;
  private static final int DEFAULT_NumUniqueInclpathsPerCmd = 3;

  private Path rootDir;
  private final int numFiles;
  private final int numUniqueDefsPerCmd;
  private final int numUniqueInclpathsPerCmd;

  private final int numUniqueFiles;
  private final Map<String, String> commonMacros = new TreeMap<>();
  private final List<String> commonInclpaths = new ArrayList<>();

  private final EstimatedMemoryStats commonStats = new EstimatedMemoryStats();
  private final EstimatedMemoryStats accumulatedFileStats = new EstimatedMemoryStats();

  /**
   * @param numFiles
   * @param numUniqueCommonDefs
   * @param numUniqueCommonInclpaths
   * @param rootDir
   * @param percentUniqueCmds
   * @param numUniqueDefsPerCmd
   * @param numUniqueInclpathsPerCmd
   */
  public ProjectGenerator(int numFiles, int numUniqueCommonDefs, int numUniqueCommonInclpaths, Path rootDir,
      int percentUniqueCmds, int numUniqueDefsPerCmd, int numUniqueInclpathsPerCmd) {
    this.numFiles = Math.max(0, numFiles);
    this.rootDir = Objects.requireNonNull(rootDir, "rootDir");
    this.numUniqueDefsPerCmd = numUniqueDefsPerCmd;
    this.numUniqueInclpathsPerCmd = numUniqueInclpathsPerCmd;

    // sanitize input
    {
      percentUniqueCmds = Math.max(0, percentUniqueCmds);
      percentUniqueCmds = Math.min(percentUniqueCmds, 100);
      // we specify unique args on the 'add_executable' level only..
      // number of unique files to build
      numUniqueFiles = (int) Math.round(this.numFiles * (percentUniqueCmds / 100.0));
    }

    for (int i = 0; i < numUniqueCommonDefs; i++) {
      commonMacros.put(String.format("COMMON_MACRO_%d", i + 1), String.format("471%d", i + 1));
    }
    for (int i = 0; i < numUniqueCommonInclpaths; i++) {
      commonInclpaths.add(String.format("%s/include/common_%d", i % 2 == 0 ? "src" : "/usr/local/com.example", i));
    }
  }

  public static void main(String[] args) {
    ProjectGenerator generator;
    if ((generator = parseArgs(args)) == null) {
      System.err.flush();
      usage();
    } else {
      try {
        generator.generate();
        System.exit(0);
      } catch (IOException e) {
        System.err.println(e.getLocalizedMessage());
      }
    }
    System.exit(1);
  }

  /**
   * @throws IOException
   *
   */
  public void generate() throws IOException {
    rootDir = Files.createDirectories(rootDir);
    commonStats.countMacros(commonMacros);
    commonStats.countInclpaths(commonInclpaths);
    createTopLevel();
  }

  /**
   * @param uniquifier
   *          a unique string that can be used to create unique file names, macros or include paths
   * @param withUniqueOptions
   * @return the relative source directory name of the directory created
   * @throws IOException
   */
  private String createSubLevel(String uniquifier, boolean withUniqueOptions) throws IOException {
    Path relDir = Paths.get("src", "src_" + uniquifier);

    String mainName = String.format("main_%s", uniquifier);
    String mainSourceName = String.format("%s.%s", mainName, "c");

    Map<String, String> macros = new TreeMap<>();
    List<String> incls = new ArrayList<>();
    if (withUniqueOptions) {
      for (int i = 0; i < numUniqueDefsPerCmd; i++) {
        macros.put(String.format("UNIQ_MACRO_%s_%d", uniquifier, i), String.format("%d%s", i + 1, uniquifier));
      }
      for (int i = 0; i < numUniqueInclpathsPerCmd; i++) {
        incls.add(String.format("%s/include/uniq%s_%d", i % 2 == 0 ? "src" : "/usr/local/com.example", uniquifier, i));
      }
      accumulatedFileStats.countMacros(macros);
      accumulatedFileStats.countInclpaths(incls);
    }

    // create source file
    Path dir = Files.createDirectories(rootDir.resolve(relDir));
    writeMainSourceFile(dir, mainSourceName, macros);
    // create CMakeLists.txt
    try (PrintStream os = new PrintStream(Files.newOutputStream(dir.resolve("CMakeLists.txt")), true)) {
      // add executable in cmakelists
      os.format("add_executable(%s %s)%n", mainName, mainSourceName);
      if (withUniqueOptions) {
        // unique compile options...
        int i = 0;
        os.format("# non-common include paths to compile %s (-I compiler option)%n", mainSourceName);
        for (String incl : incls) {
          os.format("target_include_directories(%s %sPUBLIC %s)%n", mainName, i++ % 3 == 0 ? "" : "SYSTEM ", incl);
        }
        os.format("# non-common macros to compile %s (-D compiler option)%n", mainSourceName);
        for (Entry<String, String> define : macros.entrySet()) {
          os.format("target_compile_definitions(%s PUBLIC -D%s=%s)%n", mainName, define.getKey(), define.getValue());
        }
      }
      return relDir.toString();
    }
  }

  /**
   * @param dir
   * @param fileName
   *          the relative source file name of the file created
   * @param macros
   * @throws IOException
   */
  private void writeMainSourceFile(Path dir, String fileName, Map<String, String> macros) throws IOException {
    try (PrintStream os = new PrintStream(Files.newOutputStream(dir.resolve(fileName)), true)) {
      os.format("/* %s -- generated file */%n%n", fileName);
      os.format("#include <stdio.h>  // on built-in include path%n");
      os.format("#include <stdlib.h> // on built-in include path%n");
      os.println();
      os.print("/* Conditionals to show whether your IDE detects preprocessor symbols... */");
      printConditionals(os, commonMacros);
      printConditionals(os, macros);
      os.println();
      os.format("%nint main(int argc, char **argv) {%n  puts(\"!!! %s says hello.\");%n", fileName);
      os.format("  return EXIT_SUCCESS;%n}%n");
    }
  }

  private void printConditionals(PrintStream os, Map<String, String> macros) throws IOException {
    for (Entry<String, String> macro : macros.entrySet()) {
      os.format("%n#if defined(%1$s)%n\t// is macro '%1$s' recognized by IDE?", macro.getKey());
      os.format("%n#else %n\t// macro '%1$s' is NOT recognized by IDE", macro.getKey());
      os.format("%n#endif // %1$s", macro.getKey());

      os.format("%n#if %1$s-0 == %2$s%n\t// is macro value '%1$s=%2$s' recognized by IDE?", macro.getKey(),
          macro.getValue());
      os.format("%n#else %n\t// macro value '%1$s' is NOT recognized by IDE", macro.getValue());
      os.format("%n#endif // %1$s == %2$s", macro.getKey(), macro.getValue());
    }
    os.println();
  }

  /**
   * @throws IOException
   *
   */
  private void createTopLevel() throws IOException {
    int numSubDirs = numFiles;

    // write top level CMakeListst.txt..
    try (PrintStream os = new PrintStream(Files.newOutputStream(rootDir.resolve("CMakeLists.txt")), true)) {
      int numUniqueCommonDefs = commonMacros.size();
      int numUniqueCommonInclpaths = commonInclpaths.size();
      os.format("# Auto-generated project for CDT performance testing.%n");
      os.format("# Generated with %s%n%n", ProjectGenerator.class.getName());
      os.println("cmake_minimum_required(VERSION 2.8.12)");

      os.format("%n# This project has %,d source files, each to compile with%n", numFiles);
      os.format("# - %d common preprocessor symbols (-D compiler option) and%n"
          + "# - %d common include paths (-I compiler option)%n", numUniqueCommonDefs, numUniqueCommonInclpaths);
      os.println("# on each compiler command line.");

      os.format("#%n# %d (%d percent) of the source files compile with%n", numUniqueFiles,
          (int) (numUniqueFiles * 100.0 / numFiles));
      os.format(
          "# - %d additional unique preprocessor symbols (-D compiler option) and%n"
              + "# - %d additional unique include paths (-I compiler option).%n",
          numUniqueDefsPerCmd, numUniqueInclpathsPerCmd);

      os.format(Locale.ROOT, "%nproject(\"HUGE-%d/%dcD-%dcI-%duD-%duI\")%n", numFiles, numUniqueCommonDefs,
          numUniqueCommonInclpaths, numUniqueDefsPerCmd, numUniqueInclpathsPerCmd);

      os.format("%n# macros passed to each source file (-D compiler option) -> %dcD%n", numUniqueCommonDefs);
      for (Entry<String, String> define : commonMacros.entrySet()) {
        os.format("add_definitions(-D%s=%1s)%n", define.getKey(), define.getValue());
      }

      os.format("%n# include paths passed to each source file (-I compiler option) -> %dcI%n",
          numUniqueCommonInclpaths);
      int i = 0;
      for (String incl : commonInclpaths) {
        os.format("include_directories(%s%s)%n", i++ % 2 == 0 ? "" : "SYSTEM ", incl);
      }

      ArrayList<Object> dirs = new ArrayList<>();
      for (i = 0; i < numSubDirs; i++) {
        dirs.add(createSubLevel(String.format("%04d", i), i < numUniqueFiles));
      }

      os.println();
      printEstimatedMemoryUsage(os);

      os.println();
      for (Object dir : dirs) {
        os.format("add_subdirectory(%s)%n", dir);
      }
    }
  }

  /**
   * @param os
   */
  private void printEstimatedMemoryUsage(PrintStream os) {
    os.println("# ---------------------------------------------------------------------------");
    os.println("# Estimated minimum JVM memory consuption");
    os.println("#");
    os.println("# After extracting the include paths and preprocessor symbols");
    os.println("# from the compile_command.json file and elimination of duplicate strings,");
    os.println("# at minimum the following memory is required:");
    os.println("#");
    os.format("#\t%,d characters for names of %,d common macros%n", commonStats.numMacroNameChars,
        commonStats.numMacros);
    os.format("#\t%,d characters for values of %,d common macros%n", commonStats.numMacroValueChars,
        commonStats.numMacros);
    os.format("#\t%,d characters for %,d common include paths%n", commonStats.numInclPathChars,
        commonStats.numInclPaths);
    os.println("#");
    os.format("#\t%,d characters for names of %,d unique macros%n", accumulatedFileStats.numMacroNameChars,
        accumulatedFileStats.numMacros);
    os.format("#\t%,d characters for values of %,d unique macros%n", accumulatedFileStats.numMacroValueChars,
        accumulatedFileStats.numMacros);
    os.format("#\t%,d characters for %,d unique include paths%n", accumulatedFileStats.numInclPathChars,
        accumulatedFileStats.numInclPaths);
    os.println("#");
    os.format("# Total with elimination of duplicate strings:%n#\t%,d characters in %,d string objects.%n",
        commonStats.numMacroNameChars + commonStats.numMacroValueChars + commonStats.numInclPathChars
            + accumulatedFileStats.numMacroNameChars + accumulatedFileStats.numMacroValueChars
            + accumulatedFileStats.numInclPathChars,
        commonStats.numMacros * 2 + commonStats.numInclPaths + accumulatedFileStats.numMacros * 2
            + accumulatedFileStats.numInclPaths);
    os.println("#");
    os.format("# Total when keeping duplicate strings:%n#\t%,d characters in %,d string objects.%n",
        (commonStats.numMacroNameChars + commonStats.numMacroValueChars) * numFiles
            + commonStats.numInclPathChars * numFiles + accumulatedFileStats.numMacroNameChars
            + accumulatedFileStats.numMacroValueChars + accumulatedFileStats.numInclPathChars,
        commonStats.numMacros * 2 * numFiles + commonStats.numInclPaths * numFiles + accumulatedFileStats.numMacros * 2
            + accumulatedFileStats.numInclPaths);
    os.println("# ---------------------------------------------------------------------------");
  }

  /**
   *
   */
  private static void usage() {
    System.out.format("CMake project generator%n");
    System.out.format("Usage:%n");
    System.out.format(
        "  %s [-n <number>] [-p <percent>]"
            + " [-cD <number>] [-cI <number>] [-uD <number>] [-uI <number>] -o <output directory>%n",
        ProjectGenerator.class.getName());
    System.out.format("Options:%n");
    System.out.format("  -n <number>:            number of source files to create (default %d)%n", DEFAULT_NumCmds);
    System.out.format(
        "  -p <percent>:           percentage of source files with unique preprocessor symbols%n"
            + "                          and include path on the command line (default %d)%n",
        DEFAULT_PercentUniqueCmd);
    System.out.format("  -cD <number>:           number of common preprocessor symbols per source file (default %d)%n",
        DEFAULT_NumCommonDefsPerCmd);
    System.out.format("  -cI <number>:           number of common include paths per source file (default %d)%n",
        DEFAULT_NumCommonInclpathsPerCmdDef);
    System.out.format("  -uD <number>:           number of unique preprocessor symbols per source file (default %d)%n",
        DEFAULT_NumUniqueDefsPerCmd);
    System.out.format("  -uI <number>:           number of unique include paths per source file (default %d)%n",
        DEFAULT_NumUniqueInclpathsPerCmd);
    System.out.format("  -o <output directory>:  directory where to create the project files%n");
  }

  private static ProjectGenerator parseArgs(String[] args) {
    Path rootDir = null;
    int numFiles = DEFAULT_NumCmds;
    int numUniqueCommonDefs = DEFAULT_NumCommonDefsPerCmd;
    int numUniqueCommonInclpaths = DEFAULT_NumCommonInclpathsPerCmdDef;
    int numUniqueDefsPerCmd = DEFAULT_NumUniqueDefsPerCmd;
    int numUniqueInclpathsPerCmd = DEFAULT_NumUniqueInclpathsPerCmd;
    int percentUniqueCmd = DEFAULT_PercentUniqueCmd;

    int argCnt = args.length;
    if (argCnt < 1) {
      return null;
    }
    int i = 0;
    while (i < argCnt) {
      String arg = args[i];
      switch (arg) {
      case "-n":
        if (i + 1 < argCnt) {
          try {
            numFiles = Integer.parseUnsignedInt(args[++i]);
            i++;
          } catch (NumberFormatException e) {
            System.err.format("Illegal value `%s`: %s%n", args[i], e.getMessage());
            return null; // error
          }
        } else {
          System.err.format("Option %s requires an argument%n", arg);
          return null; // error
        }
        break;
      case "-p":
        if (i + 1 < argCnt) {
          try {
            percentUniqueCmd = Integer.parseUnsignedInt(args[++i]);
            i++;
          } catch (NumberFormatException e) {
            System.err.format("Illegal value `%s`: %s%n", args[i], e.getMessage());
            return null; // error
          }
        } else {
          System.err.format("Option %s requires an argument%n", arg);
          return null; // error
        }
        break;
      case "-cD":
        if (i + 1 < argCnt) {
          try {
            numUniqueDefsPerCmd = Integer.parseUnsignedInt(args[++i]);
            i++;
          } catch (NumberFormatException e) {
            System.err.format("Illegal value `%s`: %s%n", args[i], e.getMessage());
            return null; // error
          }
        } else {
          System.err.format("Option %s requires an argument%n", arg);
          return null; // error
        }
        break;
      case "-cI":
        if (i + 1 < argCnt) {
          try {
            numUniqueInclpathsPerCmd = Integer.parseUnsignedInt(args[++i]);
            i++;
          } catch (NumberFormatException e) {
            System.err.format("Illegal value `%s`: %s%n", args[i], e.getMessage());
            return null; // error
          }
        } else {
          System.err.format("Option %s requires an argument%n", arg);
          return null; // error
        }
        break;
      case "-uD":
        if (i + 1 < argCnt) {
          try {
            numUniqueDefsPerCmd = Integer.parseUnsignedInt(args[++i]);
            i++;
          } catch (NumberFormatException e) {
            System.err.format("Illegal value `%s`: %s%n", args[i], e.getMessage());
            return null; // error
          }
        } else {
          System.err.format("Option %s requires an argument%n", arg);
          return null; // error
        }
        break;
      case "-uI":
        if (i + 1 < argCnt) {
          try {
            numUniqueInclpathsPerCmd = Integer.parseUnsignedInt(args[++i]);
            i++;
          } catch (NumberFormatException e) {
            System.err.format("Illegal value `%s`: %s%n", args[i], e.getMessage());
            return null; // error
          }
        } else {
          System.err.format("Option %s requires an argument%n", arg);
          return null; // error
        }
        break;
      case "-o":
        if (i + 1 < argCnt) {
          rootDir = Paths.get(args[++i]);
          i++;
        } else {
          System.err.format("Option %s requires an argument%n", arg);
          return null; // error
        }
        break;
      default:
        System.err.format("Unknown option `%s`%n", arg);
        return null; // error
      }
    }

    if (rootDir == null) {
      System.err.format("Missing output directory%n");
      return null; // error
    }

    ProjectGenerator generator = new ProjectGenerator(numFiles, numUniqueCommonDefs, numUniqueCommonInclpaths, rootDir,
        percentUniqueCmd, numUniqueDefsPerCmd, numUniqueInclpathsPerCmd);
    return generator; // success
  }

  private static class EstimatedMemoryStats {
    private int numMacroNameChars;
    private int numMacroValueChars;
    private int numInclPathChars;
    private int numMacros;
    private int numInclPaths;

    void countMacros(Map<String, String> macros) {
      for (Entry<String, String> macro : macros.entrySet()) {
        numMacroNameChars += macro.getKey().length();
        numMacroValueChars += macro.getValue().length();
      }
      numMacros += macros.size();
    }

    void countInclpaths(Collection<String> inclPaths) {
      for (String string : inclPaths) {
        numInclPathChars += string.length();
      }
      numInclPaths += inclPaths.size();
    }
  }
}
