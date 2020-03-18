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
import java.util.List;
import java.util.Locale;

/**
 * Generates a project to be build using cmake with lots of source files.
 *
 */
public class ProjectGenerator {
  private static final int numFilesDef = 1000, percentUniqueCmdDef = 100, numUniqueDefsPerFileDef = 2,
      numUniqueInclpathsPerFileDef = 2;

  private Path rootDir;
  private int numFiles = numFilesDef;
  private int percentUniqueCmd = percentUniqueCmdDef;
  private int numUniqueDefsPerFile = numUniqueDefsPerFileDef;
  private int numUniqueInclpathsPerFile = numUniqueInclpathsPerFileDef;

  /**
   * @param args
   */
  private ProjectGenerator(String[] args) {
    if (!parseArgs(args)) {
      System.err.flush();
      usage();
      System.exit(1);
    }
  }

  public static void main(String[] args) {
    ProjectGenerator main = new ProjectGenerator(args);
    try {
      main.run();
      System.exit(0);
    } catch (IOException e) {
      System.err.println(e.getLocalizedMessage());
      System.exit(1);
    }
  }

  /**
   * @throws IOException
   *
   */
  private void run() throws IOException {
    rootDir = Files.createDirectories(rootDir);
    createTopLevel();
  }

  /**
   * @param uniquifier
   *          a unique string that can be used to create unique file names, macros or include paths
   * @param sourceNum
   *          number of source files per executable
   * @return the relative source directory name of the directory created
   * @throws IOException
   */
  private String createSubLevel(String uniquifier, int sourceNum) throws IOException {
    Path relDir = Paths.get("src", "src_" + uniquifier);
    Path dir = Files.createDirectories(rootDir.resolve(relDir));

    // create CMakeLists.txt
    try (PrintStream os = new PrintStream(Files.newOutputStream(dir.resolve("CMakeLists.txt")), true)) {
      for (int i = 0; i < numUniqueInclpathsPerFile; i++) {
        os.format("include_directories(%s/include/dir/%s_%d)%n", i % 2 == 0 ? "" : "SYSTEM ", uniquifier, i);
      }
      for (int i = 0; i < numUniqueDefsPerFile; i++) {
        os.format("add_definitions(-DFOO%1$s_%2$d=%2$d)%n", uniquifier, i);
      }
      String executableName = String.format("executable_%s", uniquifier);
      os.format("%nadd_executable(%s%n", executableName);

      // create files (except main) for the executable
      List<String> sources = new ArrayList<>();
      for (int i = 1; i < sourceNum; i++) {
        String fileBaseName = String.format("src_%04d", i);
        sources.add(writeSources(dir, fileBaseName));
      }

      // add to cmakelists
      for (String f : sources) {
        os.format("  %s%n", f);
      }

      String mainName = String.format("main_%s", uniquifier);
      String mainFileName = writeMainSourceFile(dir, mainName);
      os.format("  %s%n)%n", mainFileName);
      os.format("target_compile_definitions(%s PUBLIC OHU=1 MAGIC=12348765)%n", executableName);

      return relDir.toString();
    }
  }

  /**
   * @param dir
   * @return the relative source file name of the file created
   * @throws IOException
   */
  private String writeMainSourceFile(Path dir, String fileBaseName) throws IOException {
    String fName = String.format("%s.%s", fileBaseName, "c");
    try (PrintStream os = new PrintStream(Files.newOutputStream(dir.resolve(fName)), true)) {
      os.format("#include <stdio.h>%n");
      os.format("#include <stdlib.h>%n");
      os.format("int main(int argc, char **argv) {%n  puts(\"!!! %s %s says hello!!!\" );%n", dir.toString(), fName);
      os.format("  return EXIT_SUCCESS;%n}%n");
    }
    return fName;
  }

  /**
   * @param dir
   * @param fileBaseName
   * @return the relative source file name of the file created
   * @throws IOException
   */
  private String writeSources(Path dir, String fileBaseName) throws IOException {
    String fName = String.format("%s.%s", fileBaseName, "c");
    try (PrintStream os = new PrintStream(Files.newOutputStream(dir.resolve(fName)), true)) {
      os.format("#include <stdio.h>%n");
      os.format("void fun_%s(void) {%n  puts(\" !! %s %s says hello!\" );%n}%n", fileBaseName, dir.toString(), fName);
    }
    return fName;
  }

  /**
   * @throws IOException
   *
   */
  private void createTopLevel() throws IOException {
    // sanitize input
    percentUniqueCmd = Math.max(0, percentUniqueCmd);
    percentUniqueCmd = Math.min(percentUniqueCmd, 100);
    double ratioUnique = percentUniqueCmd / 100.0;

    // we can specify unique args on the 'add_executable' level only..
    // number of executables to build
    double numExecs;
    if (ratioUnique == 0.0) {
      numExecs = 1;
    } else {
      numExecs = (int) (numFiles * ratioUnique);
    }
    // number of source files per executable
    int numSfpe = (int) (numFiles / numExecs);

    int numSubDirs = (int) Math.round(numExecs);

    try (PrintStream os = new PrintStream(Files.newOutputStream(rootDir.resolve("CMakeLists.txt")), true)) {
      os.format("# Auto-generated project for CDT performance testing.%n%n");
      os.println("cmake_minimum_required(VERSION 2.8)");
      os.format("%n# This project has%n# - %d source files with%n" + "# - %d%% unique compiler command lines.%n",
          numSubDirs, percentUniqueCmd);
      os.format("# Each file`s compiler command line has%n# - %d unique preprocessor symbol definitions and%n"
          + "# - %d unique include paths.%n%n", numUniqueDefsPerFile, numUniqueInclpathsPerFile);
      os.format(Locale.ROOT, "project(\"HUGE-%d/%d%% uniq\")%n%n", numSubDirs, percentUniqueCmd);
      os.println("include_directories(\"${PROJECT_BINARY_DIR}\")");
      os.println();
      for (int i = 0; i < numSubDirs; i++) {
        String dir = createSubLevel(String.format("%04d", i), numSfpe);
        os.format("add_subdirectory(%s)%n", dir);
      }
    }
  }

  /**
   *
   */
  private void usage() {
    System.out.format("CMake project generator%n");
    System.out.format("Usage:%n");
    System.out.format("  %s [-n <number>] [-p <percent>] -o <output directory>%n", ProjectGenerator.class.getName());
    System.out.format("Options:%n");
    System.out.format("  -o <output directory>:  directory where to create the project files%n");
    System.out.format("  -n <number>:            number of source files to create (default %d)%n", numFilesDef);
    System.out.format("  -p <percent>:           percentage of source files with unique preprocessor symbols%n"
        + "                          and include path on the command line (default %d)%n", percentUniqueCmdDef);
  }

  private boolean parseArgs(String[] args) {
    int argCnt = args.length;
    if (argCnt < 1) {
      usage();
      return false;
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
            System.err.format("Illegal value `%s`: %s%n", args[++i], e.getMessage());
            return false; // error
          }
        } else {
          System.err.format("Option %s requires an argument%n", arg);
          return false; // error
        }
        break;
      case "-p":
        if (i + 1 < argCnt) {
          try {
            percentUniqueCmd = Integer.parseUnsignedInt(args[++i]);
            i++;
          } catch (NumberFormatException e) {
            System.err.format("Illegal value `%s`: %s%n", args[++i], e.getMessage());
            return false; // error
          }
        } else {
          System.err.format("Option %s requires an argument%n", arg);
          return false; // error
        }
        break;
      case "-o":
        if (i + 1 < argCnt) {
          rootDir = Paths.get(args[++i]);
          i++;
        } else {
          System.err.format("Option %s requires an argument%n", arg);
          return false; // error
        }
        break;
      default:
        System.err.format("Unknown option `%s`%n", arg);
        return false; // error

      }
    }

    return true; // success
  }
}
