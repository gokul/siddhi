/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.siddhi.doc.gen.core.utils;

import com.google.common.io.Files;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;
import io.siddhi.annotation.Example;
import io.siddhi.annotation.Extension;
import io.siddhi.annotation.Parameter;
import io.siddhi.annotation.ParameterOverload;
import io.siddhi.annotation.ReturnAttribute;
import io.siddhi.annotation.SystemParameter;
import io.siddhi.doc.gen.core.freemarker.FormatDescriptionMethod;
import io.siddhi.doc.gen.extensions.ExtensionDocCache;
import io.siddhi.doc.gen.extensions.ExtensionDocRetriever;
import io.siddhi.doc.gen.metadata.ExampleMetaData;
import io.siddhi.doc.gen.metadata.ExtensionMetaData;
import io.siddhi.doc.gen.metadata.ExtensionType;
import io.siddhi.doc.gen.metadata.NamespaceMetaData;
import io.siddhi.doc.gen.metadata.ParameterMetaData;
import io.siddhi.doc.gen.metadata.ParameterOverloadMetaData;
import io.siddhi.doc.gen.metadata.ReturnAttributeMetaData;
import io.siddhi.doc.gen.metadata.SystemParameterMetaData;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.atteo.classindex.ClassIndex;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * Utility class for getting the meta data for the extension processors in Siddhi
 */
public class DocumentationUtils {
    private DocumentationUtils() {   // To prevent instantiating utils class
    }

    /**
     * Returns the extension extension meta data
     * Gets the meta data from the siddhi manager
     *
     * @param targetDirectoryPath The path of the target directory of the maven module containing extensions
     * @param logger              The maven plugin logger
     * @param includeOrigin
     * @return NamespaceMetaData namespace meta data list
     * @throws MojoFailureException   If this fails to access project dependencies
     * @throws MojoExecutionException If the classes directory from which classes are loaded is invalid
     */
    public static List<NamespaceMetaData> getExtensionMetaData(String targetDirectoryPath,
                                                               List<String> runtimeClasspathElements,
                                                               Log logger, boolean includeOrigin)
            throws MojoFailureException, MojoExecutionException {
        List<NamespaceMetaData> namespaceMetaDataList = new ArrayList<NamespaceMetaData>();
        File classesDirectory = new File(targetDirectoryPath);
        try {
            List<File> jarFiles = new ArrayList<>();
            listOfJarFiles(targetDirectoryPath, jarFiles);
            // +1 to include the module's target/classes folder
            int urlCount = runtimeClasspathElements.size() + jarFiles.size() + 1;
            // Creating a list of URLs with all project dependencies
            URL[] urls = new URL[urlCount];
            int index = 0;
            for (; index < runtimeClasspathElements.size(); index++) {
                try {
                    urls[index] = new File(runtimeClasspathElements.get(index)).toURI().toURL();
                } catch (MalformedURLException e) {
                    throw new MojoFailureException("Unable to access project dependency: "
                            + runtimeClasspathElements.get(index), e);
                }
            }

            for (int j = 0; index < urlCount - 1; index++, j++) {
                try {
                    urls[index] = jarFiles.get(j).toURI().toURL();
                } catch (MalformedURLException e) {
                    throw new MojoFailureException("Unable to access project dependency: "
                            + runtimeClasspathElements.get(index), e);
                }
            }

            // Adding the generated classes to the class loader
            urls[urlCount - 1] = classesDirectory.toURI().toURL();
            ClassLoader urlClassLoader = AccessController.doPrivileged(
                    (PrivilegedAction<ClassLoader>) () -> new URLClassLoader(
                            urls, Thread.currentThread().getContextClassLoader()
                    )
            );

            Iterable<Class<?>> extensions = ClassIndex.getAnnotated(Extension.class, urlClassLoader);
            for (Class extension : extensions) {
                addExtensionMetaDataIntoNamespaceList(namespaceMetaDataList, extension, logger, includeOrigin);
            }
        } catch (MalformedURLException e) {
            throw new MojoExecutionException("Invalid classes directory: " + classesDirectory.getAbsolutePath(), e);
        }
        for (NamespaceMetaData aNamespaceMetaData : namespaceMetaDataList) {
            for (List<ExtensionMetaData> extensionMetaData : aNamespaceMetaData.getExtensionMap().values()) {
                Collections.sort(extensionMetaData);
            }
        }
        Collections.sort(namespaceMetaDataList);
        return namespaceMetaDataList;
    }

    private static Manifest getManifest(Class<?> clz) {
        String resource = "/" + clz.getName().replace(".", "/") + ".class";
        String fullPath = clz.getResource(resource).toString();
        String archivePath = fullPath.substring(0, fullPath.length() - resource.length());
        if (archivePath.endsWith("\\WEB-INF\\classes") || archivePath.endsWith("/WEB-INF/classes")) {
            archivePath = archivePath.substring(0, archivePath.length() - "/WEB-INF/classes".length()); // Required for wars
        }

        try (InputStream input = new URL(archivePath + "/META-INF/MANIFEST.MF").openStream()) {
            return new Manifest(input);
        } catch (Exception e) {
            throw new RuntimeException("Loading MANIFEST for class " + clz + " failed!", e);
        }
    }

    private static void listOfJarFiles(String directoryName, List<File> files) {
        File directory = new File(directoryName);

        // Get all files from a directory.
        File[] fList = directory.listFiles();
        if (fList != null) {
            for (File file : fList) {
                if (file.isFile() && file.getName().endsWith(".jar")) {
                    files.add(file);
                } else if (file.isDirectory()) {
                    listOfJarFiles(file.getAbsolutePath(), files);
                }
            }
        }
    }


    /**
     * Generate documentation related files using metadata
     *
     * @param namespaceMetaDataList      Metadata in this repository
     * @param documentationBaseDirectory The path of the directory in which the documentation will be generated
     * @param documentationVersion       The version of the documentation being generated
     * @param logger                     The logger to log errors
     * @throws MojoFailureException if the Mojo fails to find template file or create new documentation file
     */
    public static void generateDocumentation(List<NamespaceMetaData> namespaceMetaDataList,
                                             String documentationBaseDirectory, String documentationVersion, Log logger)
            throws MojoFailureException {
        // Generating data model
        Map<String, Object> rootDataModel = new HashMap<>();
        rootDataModel.put("metaData", namespaceMetaDataList);
        rootDataModel.put("formatDescription", new FormatDescriptionMethod());
        rootDataModel.put("latestDocumentationVersion", documentationVersion);

        String outputFileRelativePath = Constants.API_SUB_DIRECTORY + File.separator + documentationVersion
                + Constants.MARKDOWN_FILE_EXTENSION;

        generateFileFromTemplate(
                Constants.MARKDOWN_DOCUMENTATION_TEMPLATE + Constants.MARKDOWN_FILE_EXTENSION
                        + Constants.FREEMARKER_TEMPLATE_FILE_EXTENSION,
                rootDataModel, documentationBaseDirectory, outputFileRelativePath
        );
        File newVersionFile = new File(documentationBaseDirectory + File.separator + outputFileRelativePath);
        File latestLabelFile = new File(documentationBaseDirectory + File.separator +
                Constants.API_SUB_DIRECTORY + File.separator + Constants.LATEST_FILE_NAME +
                Constants.MARKDOWN_FILE_EXTENSION);
        try {
            Files.copy(newVersionFile, latestLabelFile);
        } catch (IOException e) {
            logger.warn("Failed to generate latest.md file", e);
        }
    }

    /**
     * Update the documentation home page
     *
     * @param inputFile                  The path to the input file
     * @param outputFile                 The path to the output file
     * @param extensionRepositoryName    The name of  the extension repository
     * @param latestDocumentationVersion The version of the latest documentation generated
     * @param namespaceMetaDataList      Metadata in this repository
     * @throws MojoFailureException if the Mojo fails to find template file or create new documentation file
     */
    public static void updateHeadingsInMarkdownFile(File inputFile, File outputFile,
                                                    String extensionRepositoryName,
                                                    String latestDocumentationVersion,
                                                    List<NamespaceMetaData> namespaceMetaDataList)
            throws MojoFailureException {
        // Retrieving the content of the README.md file
        List<String> inputFileLines = new ArrayList<>();
        try {
            inputFileLines = Files.readLines(inputFile, Constants.DEFAULT_CHARSET);
        } catch (IOException ignored) {
        }

        // Generating data model
        Map<String, Object> rootDataModel = new HashMap<>();
        rootDataModel.put("inputFileLines", inputFileLines);
        rootDataModel.put("extensionRepositoryName", extensionRepositoryName);
        rootDataModel.put("latestDocumentationVersion", latestDocumentationVersion);
        rootDataModel.put("metaData", namespaceMetaDataList);
        rootDataModel.put("formatDescription", new FormatDescriptionMethod());

        generateFileFromTemplate(
                Constants.MARKDOWN_HEADINGS_UPDATE_TEMPLATE + Constants.MARKDOWN_FILE_EXTENSION
                        + Constants.FREEMARKER_TEMPLATE_FILE_EXTENSION,
                rootDataModel, outputFile.getParent(), outputFile.getName()
        );
    }

    /**
     * Remove the snapshot version documentation files from docs/api directory
     *
     * @param mkdocsConfigFile           The mkdocs configuration file
     * @param documentationBaseDirectory The path of the base directory in which the documentation will be generated
     * @param logger                     The maven plugin logger
     */
    public static void removeSnapshotAPIDocs(File mkdocsConfigFile, String documentationBaseDirectory, Log logger) {
        // Retrieving the documentation file names
        File apiDocsDirectory = new File(documentationBaseDirectory
                + File.separator + Constants.API_SUB_DIRECTORY);
        String[] documentationFileNames = apiDocsDirectory.list(
                (directory, fileName) -> fileName.endsWith(Constants.MARKDOWN_FILE_EXTENSION)
        );

        if (documentationFileNames != null) {
            // Removing snapshot files and creating a list of the files that are left out
            for (String documentationFileName : documentationFileNames) {
                if (documentationFileName.endsWith(Constants.SNAPSHOT_VERSION_POSTFIX
                        + Constants.MARKDOWN_FILE_EXTENSION)) {
                    // Removing the snapshot documentation file
                    File documentationFile = new File(apiDocsDirectory.getAbsolutePath()
                            + File.separator + documentationFileName);
                    if (!documentationFile.delete()) {
                        logger.warn("Failed to delete SNAPSHOT documentation file "
                                + documentationFile.getAbsolutePath());
                    }
                }
            }

        }
    }

    /**
     * This add a new page to the list of pages in the mkdocs configuration
     *
     * @param mkdocsConfigFile           The mkdocs configuration file
     * @param documentationBaseDirectory The base directory of the documentation
     * @throws FileNotFoundException If mkdocs configuration file is not found
     */
    public static void updateAPIPagesInMkdocsConfig(File mkdocsConfigFile, String documentationBaseDirectory)
            throws FileNotFoundException {
        // Retrieving the documentation file names
        File documentationDirectory = new File(documentationBaseDirectory
                + File.separator + Constants.API_SUB_DIRECTORY);
        String[] documentationFiles = documentationDirectory.list(
                (directory, fileName) -> fileName.endsWith(Constants.MARKDOWN_FILE_EXTENSION)
        );

        List<String> apiDirectoryContent;
        if (documentationFiles == null) {
            apiDirectoryContent = new ArrayList<>();
        } else {
            apiDirectoryContent = Arrays.asList(documentationFiles);
            apiDirectoryContent.sort(new Comparator<String>() {
                @Override
                public int compare(String s1, String s2) {
                    String[] s1s = s1.split("\\D+");
                    String[] s2s = s2.split("\\D+");
                    int i = 0;
                    while (s1s.length > i || s2s.length > i) {
                        String s1a = "0";
                        String s2a = "0";
                        if (s1s.length > i) {
                            s1a = s1s[i];
                        }
                        if (s2s.length > i) {
                            s2a = s2s[i];
                        }
                        int s1aInt = Integer.parseInt(s1a);
                        int s2aInt = Integer.parseInt(s2a);
                        if (s2aInt > s1aInt) {
                            return 1;
                        } else if (s2aInt < s1aInt) {
                            return -1;
                        }
                        i++;
                    }
                    return 0;
                }
            });
        }

        // Creating yaml parser
        DumperOptions dumperOptions = new DumperOptions();
        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(dumperOptions);

        // Reading the mkdocs configuration
        Map<String, Object> yamlConfig = (Map<String, Object>) yaml.load(new InputStreamReader(
                new FileInputStream(mkdocsConfigFile), Constants.DEFAULT_CHARSET)
        );

        // Getting the pages list
        List<Map<String, Object>> yamlConfigPagesList =
                (List<Map<String, Object>>) yamlConfig.get(Constants.MKDOCS_CONFIG_PAGES_KEY);

        // Creating the new api pages list
        LinkedList<Map<String, Object>> apiPagesList = new LinkedList<>();
        for (String apiFile : apiDirectoryContent) {
            String pageName = apiFile.substring(0, apiFile.length() - Constants.MARKDOWN_FILE_EXTENSION.length());

            Map<String, Object> newPage = new HashMap<>();
            newPage.put(pageName, Constants.API_SUB_DIRECTORY + Constants.MKDOCS_FILE_SEPARATOR + apiFile);
            if (pageName.equals(Constants.LATEST_FILE_NAME)) {
                apiPagesList.addFirst(newPage);
            } else {
                apiPagesList.add(newPage);
            }
        }

        // Setting the new api pages
        Map<String, Object> yamlConfigAPIPage = null;
        for (Map<String, Object> yamlConfigPage : yamlConfigPagesList) {
            if (yamlConfigPage.get(Constants.MKDOCS_CONFIG_PAGES_API_KEY) != null) {
                yamlConfigAPIPage = yamlConfigPage;
                break;
            }
        }
        if (yamlConfigAPIPage == null) {
            yamlConfigAPIPage = new HashMap<>();
            yamlConfigPagesList.add(yamlConfigAPIPage);
        }
        yamlConfigAPIPage.put(Constants.MKDOCS_CONFIG_PAGES_API_KEY, apiPagesList);

        // Saving the updated configuration
        yaml.dump(yamlConfig, new OutputStreamWriter(
                new FileOutputStream(mkdocsConfigFile), Constants.DEFAULT_CHARSET)
        );
    }

    /**
     * Generate a extension index file from the template file
     *
     * @param extensionRepositories      The list of extension repository names
     * @param extensionRepositoryOwner   The extension repository owner's name
     * @param documentationBaseDirectory The output directory path in which the extension index will be generated
     * @param extensionsIndexFileName    The name of the index file that will be generated
     * @throws MojoFailureException if the Mojo fails to find template file or create new documentation file
     */
    public static void createExtensionsIndex(List<String> extensionRepositories,
                                             String extensionRepositoryOwner,
                                             String documentationBaseDirectory,
                                             String projectBaseDir,
                                             String extensionsIndexFileName)
            throws MojoFailureException {
        // Separating Apache and GPL extensions based on siddhi repository prefix conventions
        List<String> gplExtensionRepositories = new ArrayList<>();
        List<String> apacheExtensionRepositories = new ArrayList<>();
        for (String extensionRepository : extensionRepositories) {
            if (extensionRepository.startsWith(Constants.GITHUB_GPL_EXTENSION_REPOSITORY_PREFIX)) {
                gplExtensionRepositories.add(extensionRepository);
            } else if (extensionRepository.startsWith(Constants.GITHUB_APACHE_EXTENSION_REPOSITORY_PREFIX)) {
                apacheExtensionRepositories.add(extensionRepository);
            }
        }

        // Generating data model
        Map<String, Object> rootDataModel = new HashMap<>();
        rootDataModel.put("extensionsOwner", extensionRepositoryOwner);

        Path gplDocCachePath = Paths.get(
                projectBaseDir, "src", "main", "resources", "gpl.docs.json");
        Path apacheDocCachePath = Paths.get(
                projectBaseDir, "src", "main", "resources", "apache.docs.json");

        rootDataModel.put("gplExtensions", retrieveExtensionWithDescriptions(
                gplDocCachePath, extensionRepositoryOwner, gplExtensionRepositories));
        rootDataModel.put("apacheExtensions", retrieveExtensionWithDescriptions(
                apacheDocCachePath, extensionRepositoryOwner, apacheExtensionRepositories));

        generateFileFromTemplate(
                Constants.MARKDOWN_EXTENSIONS_INDEX_TEMPLATE + Constants.MARKDOWN_FILE_EXTENSION
                        + Constants.FREEMARKER_TEMPLATE_FILE_EXTENSION,
                rootDataModel, documentationBaseDirectory,
                extensionsIndexFileName + Constants.MARKDOWN_FILE_EXTENSION
        );
    }

    private static Map<String, String> retrieveExtensionWithDescriptions(Path cachePath,
                                                                         String extensionRepositoryOwner,
                                                                         List<String> extensions)
            throws MojoFailureException {
        ExtensionDocCache cache = new ExtensionDocCache(cachePath);
        ExtensionDocRetriever retriever = new ExtensionDocRetriever(extensionRepositoryOwner, extensions, cache);

        retriever.pull();

        boolean inMemory = cache.isInMemory();
        boolean throttled = retriever.isThrottled();

        if (throttled && inMemory) {
            throw new MojoFailureException("The API has reached the throttling limits while fetching the extensions." +
                    "The extension cache is also not available. Try again later or check whether cache is present " +
                    "in path: " + cachePath.toString());
        }
        return cache.getExtensionDescriptionMap();
    }

    /**
     * Build the mkdocs site using the mkdocs config file
     *
     * @param mkdocsConfigFile The mkdocs configuration file
     * @param logger           The maven logger
     * @return true if the documentation generation is successful
     */
    public static boolean generateMkdocsSite(File mkdocsConfigFile, Log logger) {
        boolean isDocumentationGenerationSuccessful = false;
        try {
            // Building the mkdocs site
            executeCommand(new String[]{Constants.MKDOCS_COMMAND,
                    Constants.MKDOCS_BUILD_COMMAND,
                    Constants.MKDOCS_BUILD_COMMAND_CLEAN_ARGUEMENT,
                    Constants.MKDOCS_BUILD_COMMAND_CONFIG_FILE_ARGUMENT,
                    mkdocsConfigFile.getAbsolutePath(),
                    Constants.MKDOCS_BUILD_COMMAND_SITE_DIRECTORY_ARGUMENT,
                    Constants.MKDOCS_SITE_DIRECTORY}, logger);
            isDocumentationGenerationSuccessful = true;
        } catch (Throwable t) {
            logger.warn("Failed to generate the mkdocs site.", t);
        }
        return isDocumentationGenerationSuccessful;
    }

    /**
     * Deploy the mkdocs website on GitHub pages
     *
     * @param version       The version of the documentation
     * @param baseDirectory The base directory of the project
     * @param url           The SCM URL
     * @param scmUsername   The SCM username
     * @param scmPassword   The SCM password
     * @param logger        The maven logger
     */
    public static void deployMkdocsOnGitHubPages(String version, File baseDirectory, String url, String scmUsername,
                                                 String scmPassword, Log logger) {
        try {
            // Find initial branch name
            List<String> gitStatusOutput = getCommandOutput(new String[]{Constants.GIT_COMMAND,
                    Constants.GIT_BRANCH_COMMAND}, logger);
            String initialBranch = null;
            for (String gitStatusOutputLine : gitStatusOutput) {
                if (gitStatusOutputLine.startsWith(Constants.GIT_BRANCH_COMMAND_OUTPUT_CURRENT_BRANCH_PREFIX)) {
                    initialBranch = gitStatusOutputLine.substring(
                            Constants.GIT_BRANCH_COMMAND_OUTPUT_CURRENT_BRANCH_PREFIX.length());
                }
            }

            if (initialBranch != null) {
                // Stash changes
                executeCommand(new String[]{Constants.GIT_COMMAND,
                        Constants.GIT_STASH_COMMAND}, logger);

                // Change to gh-pages branch. This will not do anything if a new branch was created in the last command.
                executeCommand(new String[]{Constants.GIT_COMMAND,
                        Constants.GIT_CHECKOUT_COMMAND,
                        Constants.GIT_GH_PAGES_BRANCH}, logger);

                // Create branch if it does not exist. This will fail if the branch exists and will not do anything.
                executeCommand(new String[]{Constants.GIT_COMMAND,
                        Constants.GIT_CHECKOUT_COMMAND,
                        Constants.GIT_CHECKOUT_COMMAND_ORPHAN_ARGUMENT,
                        Constants.GIT_GH_PAGES_BRANCH}, logger);

                executeCommand(new String[]{Constants.GIT_COMMAND,
                        Constants.GIT_PULL_COMMAND,
                        Constants.GIT_REMOTE,
                        Constants.GIT_GH_PAGES_BRANCH}, logger);

                // Getting the site that was built by mkdocs
                File siteDirectory = new File(Constants.MKDOCS_SITE_DIRECTORY);
                FileUtils.copyDirectory(siteDirectory, baseDirectory);
                String[] siteDirectoryContent = siteDirectory.list();

                // Pushing the site to GitHub (Assumes that site/ directory is ignored by git)
                if (siteDirectoryContent != null && siteDirectoryContent.length > 0) {
                    List<String> gitAddCommand = new ArrayList<>();
                    Collections.addAll(gitAddCommand, Constants.GIT_COMMAND,
                            Constants.GIT_ADD_COMMAND);
                    Collections.addAll(gitAddCommand, siteDirectoryContent);
                    executeCommand(gitAddCommand.toArray(new String[gitAddCommand.size()]), logger);

                    List<String> gitCommitCommand = new ArrayList<>();
                    Collections.addAll(gitCommitCommand, Constants.GIT_COMMAND,
                            Constants.GIT_COMMIT_COMMAND,
                            Constants.GIT_COMMIT_COMMAND_MESSAGE_ARGUMENT,
                            String.format(Constants.GIT_COMMIT_COMMAND_MESSAGE_FORMAT, version, version),
                            Constants.GIT_COMMIT_COMMAND_FILES_ARGUMENT);
                    Collections.addAll(gitCommitCommand, siteDirectoryContent);
                    executeCommand(gitCommitCommand.toArray(new String[gitCommitCommand.size()]), logger);

                    if (scmUsername != null && scmPassword != null && url != null) {
                        // Using scm username and password env var values
                        String urlWithUsernameAndPassword = url.replaceFirst(Constants.GITHUB_URL,
                                Constants.GITHUB_URL_WITH_USERNAME_PASSWORD);
                        executeCommand(new String[]{Constants.GIT_COMMAND,
                                Constants.GIT_PUSH_COMMAND,
                                String.format(urlWithUsernameAndPassword, scmUsername, scmPassword),
                                Constants.GIT_GH_PAGES_BRANCH}, logger);

                    } else {
                        // Using git credential store
                        executeCommand(new String[]{Constants.GIT_COMMAND,
                                Constants.GIT_PUSH_COMMAND,
                                Constants.GIT_REMOTE,
                                Constants.GIT_GH_PAGES_BRANCH}, logger);
                    }
                }

                // Changing back to initial branch
                executeCommand(new String[]{Constants.GIT_COMMAND,
                        Constants.GIT_CHECKOUT_COMMAND,
                        initialBranch}, logger);
                executeCommand(new String[]{Constants.GIT_COMMAND,
                        Constants.GIT_STASH_COMMAND,
                        Constants.GIT_STASH_POP_COMMAND}, logger);
            } else {
                logger.warn("Unable to parse git-status command and retrieve current git branch. " +
                        "Skipping deployment.");
            }
        } catch (Throwable t) {
            logger.warn("Failed to deploy the documentation on github pages.", t);
        }
    }

    /**
     * Commit the documentation directory and the mkdocs config file
     *
     * @param docsDirectory    The docs drectory
     * @param mkdocsConfigFile The mkdocs configuration file
     * @param readmeFile       The read me file
     * @param version          The version of the documentation
     * @param logger           The maven logger
     */
    public static void updateDocumentationOnGitHub(String docsDirectory, File mkdocsConfigFile, File readmeFile,
                                                   String version, Log logger) {
        try {
            executeCommand(new String[]{Constants.GIT_COMMAND,
                    Constants.GIT_ADD_COMMAND,
                    docsDirectory, mkdocsConfigFile.getAbsolutePath(), readmeFile.getAbsolutePath()}, logger);
            executeCommand(new String[]{Constants.GIT_COMMAND,
                    Constants.GIT_COMMIT_COMMAND,
                    Constants.GIT_COMMIT_COMMAND_MESSAGE_ARGUMENT,
                    String.format(Constants.GIT_COMMIT_COMMAND_MESSAGE_FORMAT, version, version),
                    Constants.GIT_COMMIT_COMMAND_FILES_ARGUMENT,
                    docsDirectory, mkdocsConfigFile.getAbsolutePath(), readmeFile.getAbsolutePath()}, logger);
        } catch (Throwable t) {
            logger.warn("Failed to update the documentation on GitHub repository", t);
        }
    }

    /**
     * Generate extension meta data from the annotated data in the class
     *
     * @param namespaceList  The list of namespaces to which the new extension will be added
     * @param extensionClass Class from which meta data should be extracted from
     * @param logger         The maven plugin logger
     * @param includeOrigin  Add origin to the docs
     */
    private static void addExtensionMetaDataIntoNamespaceList(List<NamespaceMetaData> namespaceList,
                                                              Class<?> extensionClass, Log logger,
                                                              boolean includeOrigin) {
        Extension extensionAnnotation = extensionClass.getAnnotation(Extension.class);

        if (extensionAnnotation != null) {      // Discarding extension classes without annotation
            ExtensionMetaData extensionMetaData = new ExtensionMetaData();

            // Finding extension type
            String extensionType = null;
            for (Map.Entry<ExtensionType, Class<?>> entry : ExtensionType.getSuperClassMap().entrySet()) {
                Class<?> superClass = entry.getValue();
                if (superClass.isAssignableFrom(extensionClass) && superClass != extensionClass) {
                    extensionType = entry.getKey().getValue();
                    break;
                }
            }

            // Discarding the extension if it belongs to an unknown type
            if (extensionType == null) {
                logger.warn("Discarding extension (belonging to an unknown extension type): "
                        + extensionClass.getCanonicalName());
                return;
            }
            extensionMetaData.setName(extensionAnnotation.name());
            extensionMetaData.setDescription(extensionAnnotation.description());

            if (includeOrigin) {
                Manifest manifest = getManifest(extensionClass);
                Attributes attributes = manifest.getMainAttributes();
                extensionMetaData.setOriginName(attributes.getValue("Bundle-Name"));
                extensionMetaData.setOriginVersion(attributes.getValue("Bundle-Version"));
            }

            // Adding query parameters
            ParameterMetaData[] parameters = new ParameterMetaData[extensionAnnotation.parameters().length];
            Map<String, ParameterMetaData> parameterMap = new HashMap<>();
            for (int i = 0; i < extensionAnnotation.parameters().length; i++) {
                Parameter parameterAnnotation = extensionAnnotation.parameters()[i];
                ParameterMetaData parameter = new ParameterMetaData();
                parameter.setName(parameterAnnotation.name());
                parameter.setType(Arrays.asList(parameterAnnotation.type()));
                parameter.setDescription(parameterAnnotation.description());
                parameter.setOptional(parameterAnnotation.optional());
                parameter.setDynamic(parameterAnnotation.dynamic());
                parameter.setDefaultValue(parameterAnnotation.defaultValue());
                parameterMap.put(parameter.getName(), parameter);
                parameters[i] = parameter;
            }
            extensionMetaData.setParameters(Arrays.asList(parameters));

            // Adding parameter overloads
            if (extensionAnnotation.parameterOverloads().length > 0) {
                ParameterOverloadMetaData[] parameterOverloads = new ParameterOverloadMetaData[
                        extensionAnnotation.parameterOverloads().length];
                for (int i = 0; i < extensionAnnotation.parameterOverloads().length; i++) {
                    ParameterOverload parameterOverloadAnnotation = extensionAnnotation.parameterOverloads()[i];
                    ParameterOverloadMetaData parameterOverload = new ParameterOverloadMetaData();
                    ParameterMetaData[] overloadParameters = new ParameterMetaData[
                            parameterOverloadAnnotation.parameterNames().length];
                    for (int j = 0; j < parameterOverloadAnnotation.parameterNames().length; j++) {
                        overloadParameters[j] = parameterMap.get(parameterOverloadAnnotation.parameterNames()[j]);
                    }
                    parameterOverload.setParameters(Arrays.asList(overloadParameters));
                    parameterOverloads[i] = parameterOverload;
                }
                extensionMetaData.setParameterOverloads(Arrays.asList(parameterOverloads));
            }
            // Adding system parameters
            SystemParameterMetaData[] systemParameters =
                    new SystemParameterMetaData[extensionAnnotation.systemParameter().length];
            for (int i = 0; i < extensionAnnotation.systemParameter().length; i++) {
                SystemParameter systemParameterAnnotation = extensionAnnotation.systemParameter()[i];

                SystemParameterMetaData systemParameter = new SystemParameterMetaData();
                systemParameter.setName(systemParameterAnnotation.name());
                systemParameter.setDescription(systemParameterAnnotation.description());
                systemParameter.setDefaultValue(systemParameterAnnotation.defaultValue());
                systemParameter.setPossibleParameters(Arrays.asList(systemParameterAnnotation.possibleParameters()));
                systemParameters[i] = systemParameter;
            }
            extensionMetaData.setSystemParameters(Arrays.asList(systemParameters));

            // Adding return attributes
            ReturnAttributeMetaData[] returnAttributes =
                    new ReturnAttributeMetaData[extensionAnnotation.returnAttributes().length];
            for (int i = 0; i < extensionAnnotation.returnAttributes().length; i++) {
                ReturnAttribute parameterAnnotation = extensionAnnotation.returnAttributes()[i];

                ReturnAttributeMetaData returnAttribute = new ReturnAttributeMetaData();
                returnAttribute.setName(parameterAnnotation.name());
                returnAttribute.setType(Arrays.asList(parameterAnnotation.type()));
                returnAttribute.setDescription(parameterAnnotation.description());
                returnAttributes[i] = returnAttribute;
            }
            extensionMetaData.setReturnAttributes(Arrays.asList(returnAttributes));

            // Adding examples
            ExampleMetaData[] examples = new ExampleMetaData[extensionAnnotation.examples().length];
            for (int i = 0; i < extensionAnnotation.examples().length; i++) {
                Example exampleAnnotation = extensionAnnotation.examples()[i];

                ExampleMetaData exampleMetaData = new ExampleMetaData();
                exampleMetaData.setSyntax(exampleAnnotation.syntax());
                exampleMetaData.setDescription(exampleAnnotation.description());
                examples[i] = exampleMetaData;
            }
            extensionMetaData.setExamples(Arrays.asList(examples));

            // Finding the namespace
            String namespaceName = extensionAnnotation.namespace();
            if (Objects.equals(namespaceName, "")) {
                namespaceName = Constants.CORE_NAMESPACE;
            }

            // Finding the relevant namespace in the namespace list
            NamespaceMetaData namespace = null;
            for (NamespaceMetaData existingNamespace : namespaceList) {
                if (Objects.equals(existingNamespace.getName(), namespaceName)) {
                    namespace = existingNamespace;
                    break;
                }
            }
            // Creating namespace if it doesn't exist
            if (namespace == null) {
                namespace = new NamespaceMetaData();
                namespace.setName(namespaceName);
                namespace.setExtensionMap(new TreeMap<>());
                namespaceList.add(namespace);
            }

            // Adding to the relevant extension metadata list in the namespace
            List<ExtensionMetaData> extensionMetaDataList = namespace.getExtensionMap()
                    .computeIfAbsent(extensionType, k -> new ArrayList<>());

            extensionMetaDataList.add(extensionMetaData);
        }
    }

    /**
     * Generate a file from a template
     *
     * @param templateFile    The template file name
     * @param dataModel       The data model to be used for generating the output files from template files
     * @param outputDirectory The output directory in which the file will be generated
     * @param outputFileName  The name of the file that will be generated
     * @throws MojoFailureException if the Mojo fails to find template file or create new documentation file
     */
    private static void generateFileFromTemplate(String templateFile, Map<String, Object> dataModel,
                                                 String outputDirectory, String outputFileName)
            throws MojoFailureException {
        // Creating the free marker configuration
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_25);
        cfg.setDefaultEncoding("UTF-8");
        cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        cfg.setClassForTemplateLoading(
                DocumentationUtils.class,
                File.separator + Constants.TEMPLATES_DIRECTORY
        );

        // Adding the constants to the freemarker data model
        Map<String, String> constantsClassFieldMap = new HashMap<>();
        for (Field field : Constants.class.getDeclaredFields()) {
            try {
                constantsClassFieldMap.put(field.getName(), field.get(null).toString());
            } catch (IllegalAccessException ignored) {  // Ignoring inaccessible variables
            }
        }
        dataModel.put("CONSTANTS", constantsClassFieldMap);

        // Adding the ExtensionType enum values to the freemarker data model
        Map<String, String> extensionTypeEnumMap = new HashMap<>();
        for (Field field : ExtensionType.class.getDeclaredFields()) {
            try {
                if (field.isEnumConstant()) {
                    extensionTypeEnumMap.put(field.getName(), ((ExtensionType) field.get(null)).getValue());
                }
            } catch (IllegalAccessException ignored) {  // Ignoring inaccessible variables
            }
        }
        dataModel.put("EXTENSION_TYPE", extensionTypeEnumMap);

        try {
            // Fetching the template
            Template template = cfg.getTemplate(templateFile);

            // Generating empty documentation files
            File outputFile = new File(outputDirectory + File.separator + outputFileName);
            if (!outputFile.getParentFile().exists()) {
                if (!outputFile.getParentFile().mkdirs()) {
                    throw new MojoFailureException("Unable to create directory " + outputFile.getParentFile());
                }
            }
            if (!outputFile.exists()) {
                if (!outputFile.createNewFile()) {
                    throw new MojoFailureException("Unable to create file " + outputFile.getAbsolutePath());
                }
            }

            // Writing to the documentation file
            try (OutputStream outputStream = new FileOutputStream(outputFile)) {
                try (Writer writer = new OutputStreamWriter(outputStream, Charset.defaultCharset())) {
                    template.process(dataModel, writer);
                }
            } catch (TemplateException e) {
                throw new MojoFailureException("Invalid Free Marker template found in " + templateFile, e);
            }
        } catch (IOException e) {
            throw new MojoFailureException("Unable to find template file " + templateFile, e);
        }
    }

    /**
     * Executing a command
     *
     * @param command The command to be executed
     * @param logger  The maven plugin logger
     * @return The output lines from executing the command
     * @throws Throwable if any error occurs during the execution of the command
     */
    private static List<String> getCommandOutput(String[] command, Log logger) throws Throwable {
        logger.info("Executing: " + String.join(" ", command));
        Process process = Runtime.getRuntime().exec(command);
        List<String> executionOutputLines = new ArrayList<>();

        // Logging the output of the command execution
        InputStream[] inputStreams = new InputStream[]{process.getInputStream(), process.getErrorStream()};
        BufferedReader bufferedReader = null;
        try {
            for (InputStream inputStream : inputStreams) {
                bufferedReader = new BufferedReader(new InputStreamReader(inputStream, Constants.DEFAULT_CHARSET));
                String commandOutput;
                while (true) {
                    commandOutput = bufferedReader.readLine();
                    if (commandOutput == null) {
                        break;
                    }

                    executionOutputLines.add(commandOutput);
                }
            }
            process.waitFor();
        } finally {
            IOUtils.closeQuietly(bufferedReader);
        }

        return executionOutputLines;
    }

    /**
     * Executing a command.
     *
     * @param command The command to be executed
     * @param logger  The maven plugin logger
     * @throws Throwable if any error occurs during the execution of the command
     */
    private static void executeCommand(String[] command, Log logger) throws Throwable {
        List<String> executionOutputLines = getCommandOutput(command, logger);
        for (String executionOutputLine : executionOutputLines) {
            logger.debug(executionOutputLine);
        }
    }
}
