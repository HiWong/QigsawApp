package org.qiyi.arch.app;

import android.databinding.tool.DataBindingBuilder;

import com.android.SdkConstants;
import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.build.api.artifact.BuildableArtifact;
import com.android.build.api.transform.QualifiedContent;
import com.android.build.api.transform.Transform;
import com.android.build.gradle.AndroidConfig;
import com.android.build.gradle.FeatureExtension;
import com.android.build.gradle.internal.FeatureModelBuilder;
import com.android.build.gradle.internal.InstantRunTaskManager;
import com.android.build.gradle.internal.SdkHandler;
import com.android.build.gradle.internal.core.GradleVariantConfiguration;
import com.android.build.gradle.internal.coverage.JacocoConfigurations;
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension;
import com.android.build.gradle.internal.incremental.BuildInfoLoaderTask;
import com.android.build.gradle.internal.incremental.InstantRunAnchorTaskConfigAction;
import com.android.build.gradle.internal.pipeline.OriginalStream;
import com.android.build.gradle.internal.pipeline.TransformManager;
import com.android.build.gradle.internal.pipeline.TransformTask;
import com.android.build.gradle.internal.publishing.AndroidArtifacts;
import com.android.build.gradle.internal.scope.CodeShrinker;
import com.android.build.gradle.internal.scope.GlobalScope;
import com.android.build.gradle.internal.scope.InternalArtifactType;
import com.android.build.gradle.internal.scope.VariantScope;
import com.android.build.gradle.internal.tasks.ExtractTryWithResourcesSupportJar;
import com.android.build.gradle.internal.transforms.CustomClassTransform;
import com.android.build.gradle.internal.transforms.D8MainDexListTransform;
import com.android.build.gradle.internal.transforms.DesugarTransform;
import com.android.build.gradle.internal.transforms.DexArchiveBuilderTransform;
import com.android.build.gradle.internal.transforms.DexArchiveBuilderTransformBuilder;
import com.android.build.gradle.internal.transforms.DexMergerTransform;
import com.android.build.gradle.internal.transforms.DexMergerTransformCallable;
import com.android.build.gradle.internal.transforms.DexSplitterTransform;
import com.android.build.gradle.internal.transforms.DexTransform;
import com.android.build.gradle.internal.transforms.ExternalLibsMergerTransform;
import com.android.build.gradle.internal.transforms.ExtractJarsTransform;
import com.android.build.gradle.internal.transforms.FixStackFramesTransform;
import com.android.build.gradle.internal.transforms.JarMergingTransform;
import com.android.build.gradle.internal.transforms.MainDexListTransform;
import com.android.build.gradle.internal.transforms.MainDexListWriter;
import com.android.build.gradle.internal.transforms.MergeClassesTransform;
import com.android.build.gradle.internal.transforms.MultiDexTransform;
import com.android.build.gradle.internal.transforms.PreDexTransform;
import com.android.build.gradle.internal.variant.BaseVariantData;
import com.android.build.gradle.options.BooleanOption;
import com.android.build.gradle.options.IntegerOption;
import com.android.build.gradle.options.ProjectOptions;
import com.android.build.gradle.options.StringOption;
import com.android.build.gradle.tasks.PreColdSwapTask;
import com.android.builder.core.AndroidBuilder;
import com.android.builder.core.DefaultDexOptions;
import com.android.builder.core.DesugarProcessArgs;
import com.android.builder.core.VariantType;
import com.android.builder.dexing.DexingType;
import com.android.builder.errors.EvalIssueException;
import com.android.builder.errors.EvalIssueReporter;
import com.android.builder.profile.Recorder;
import com.android.builder.utils.FileCache;
import com.android.ide.common.repository.GradleVersion;
import com.android.sdklib.AndroidVersion;
import com.android.utils.FileUtils;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.provider.Provider;
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static com.android.build.gradle.internal.publishing.AndroidArtifacts.ArtifactScope.MODULE;
import static com.android.build.gradle.internal.publishing.AndroidArtifacts.ArtifactType.METADATA_CLASSES;
import static com.android.build.gradle.internal.publishing.AndroidArtifacts.ConsumedConfigType.METADATA_VALUES;
import static com.android.build.gradle.internal.publishing.AndroidArtifacts.MODULE_PATH;
import static com.android.build.gradle.internal.scope.ArtifactPublishingUtil.publishArtifactToConfiguration;
import static com.android.build.gradle.internal.scope.InternalArtifactType.INSTANT_RUN_MERGED_MANIFESTS;
import static com.android.build.gradle.internal.scope.InternalArtifactType.LEGACY_MULTIDEX_MAIN_DEX_LIST;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * author: AllenWang
 * date: 2019/1/24
 */
public abstract class TaskManager extends com.android.build.gradle.internal.TaskManager {

    public TaskManager(
            @NonNull GlobalScope globalScope,
            @NonNull Project project,
            @NonNull ProjectOptions projectOptions,
            @NonNull DataBindingBuilder dataBindingBuilder,
            @NonNull AndroidConfig extension,
            @NonNull SdkHandler sdkHandler,
            @NonNull ToolingModelBuilderRegistry toolingRegistry,
            @NonNull Recorder recorder) {
        super(globalScope, project, projectOptions, dataBindingBuilder, extension, sdkHandler, toolingRegistry, recorder);
    }


    /**
     * Creates the post-compilation tasks for the given Variant.
     * <p>
     * These tasks create the dex file from the .class files, plus optional intermediary steps like
     * proguard and jacoco
     */
    public void createPostCompilationTasks(

            @NonNull final VariantScope variantScope) {

        checkNotNull(variantScope.getTaskContainer().getJavacTask());

        final BaseVariantData variantData = variantScope.getVariantData();
        final GradleVariantConfiguration config = variantData.getVariantConfiguration();

        TransformManager transformManager = variantScope.getTransformManager();

        // ---- Code Coverage first -----
        boolean isTestCoverageEnabled =
                config.getBuildType().isTestCoverageEnabled()
                        && !config.getType().isForTesting()
                        && !variantScope.getInstantRunBuildContext().isInInstantRunMode();
        if (isTestCoverageEnabled) {
            createJacocoTransform(variantScope);
        }

        maybeCreateDesugarTask(variantScope, config.getMinSdkVersion(), transformManager);

        AndroidConfig extension = variantScope.getGlobalScope().getExtension();

        // Merge Java Resources.
        createMergeJavaResTransform(variantScope);

        // ----- External Transforms -----
        // apply all the external transforms.
        List<Transform> customTransforms = extension.getTransforms();
        List<List<Object>> customTransformsDependencies = extension.getTransformsDependencies();

        for (int i = 0, count = customTransforms.size(); i < count; i++) {
            Transform transform = customTransforms.get(i);

            List<Object> deps = customTransformsDependencies.get(i);
            transformManager
                    .addTransform(taskFactory, variantScope, transform)
                    .ifPresent(
                            t -> {
                                if (!deps.isEmpty()) {
                                    t.dependsOn(deps);
                                }

                                // if the task is a no-op then we make assemble task depend on it.
                                if (transform.getScopes().isEmpty()) {
                                    variantScope.getTaskContainer().getAssembleTask().dependsOn(t);
                                }
                            });
        }

        // Add transform to create merged runtime classes if this is a feature or dynamic-feature.
        // Merged runtime classes are needed if code minification is enabled in multi-apk project.
        if (variantData.getType().isFeatureSplit()) {
            createMergeClassesTransform(variantScope);
        }

        // ----- Android studio profiling transforms
        final VariantType type = variantData.getType();
        if (variantScope.getVariantConfiguration().getBuildType().isDebuggable()
                && type.isApk()
                && !type.isForTesting()) {
            boolean addDependencies = !type.isFeatureSplit();
            for (String jar : getAdvancedProfilingTransforms(projectOptions)) {
                if (jar != null) {
                    transformManager.addTransform(
                            taskFactory,
                            variantScope,
                            new CustomClassTransform(jar, addDependencies));
                }
            }
        }

        // ----- Minify next -----
        CodeShrinker shrinker = maybeCreateJavaCodeShrinkerTransform(variantScope);
        if (shrinker == CodeShrinker.R8) {
            maybeCreateResourcesShrinkerTransform(variantScope);
            maybeCreateDexSplitterTransform(variantScope);
            // TODO: create JavaResSplitterTransform and call it here (http://b/77546738)
            return;
        }

        // ----- 10x support
        PreColdSwapTask preColdSwapTask = null;
        if (variantScope.getInstantRunBuildContext().isInInstantRunMode()) {

            DefaultTask allActionsAnchorTask = createInstantRunAllActionsTasks(variantScope);
            assert variantScope.getInstantRunTaskManager() != null;
            preColdSwapTask =
                    variantScope.getInstantRunTaskManager().createPreColdswapTask(projectOptions);
            preColdSwapTask.dependsOn(allActionsAnchorTask);

            if (!usingIncrementalDexing(variantScope)) {
                globalScope
                        .getAndroidBuilder()
                        .getIssueReporter()
                        .reportError(
                                EvalIssueReporter.Type.GENERIC,
                                new EvalIssueException(
                                        "Instant Run requires incremental dexing. Please remove '"
                                                + BooleanOption.ENABLE_DEX_ARCHIVE.name()
                                                + "=false' from gradle.properties"));
            }
        }

        // ----- Multi-Dex support
        DexingType dexingType = variantScope.getDexingType();
        //TODO 只是这样做还不行，关键还是要用自定义的VairantScope替换掉这个VariantScope, 或者hook住它，然后就可以动态改变它的方法。
        //TODO 但是要从源头上修改VariantScope太麻烦了，暂时先hook住,
        //TODO 下一步就是替换掉VariantManger中的variantScopes，这样就差不多了。
        System.out.println("variant:" + variantScope.toString() + ",dexType is as follows:");

        /*
        if (dexingType == DexingType.LEGACY_MULTIDEX) {
            System.out.println("LEGACY_MULTIDEX");
        } else if (dexingType == DexingType.NATIVE_MULTIDEX) {
            System.out.println("NATIVE_MULTIDEX");
        } else if (dexingType == DexingType.MONO_DEX) {
            System.out.println("MONO_DEX");
        }
        */
        System.out.println(dexingType.toString());

        if (dexingType == DexingType.NATIVE_MULTIDEX) {
            dexingType = DexingType.LEGACY_MULTIDEX;
        }

        // Upgrade from legacy multi-dex to native multi-dex if possible when using with a device
        if (dexingType == DexingType.LEGACY_MULTIDEX) {
            if (variantScope.getVariantConfiguration().isMultiDexEnabled()
                    && variantScope
                    .getVariantConfiguration()
                    .getMinSdkVersionWithTargetDeviceApi()
                    .getFeatureLevel()
                    >= 21) {
                dexingType = DexingType.NATIVE_MULTIDEX;
            }
        }

        if (dexingType == DexingType.LEGACY_MULTIDEX) {
            boolean proguardInPipeline = variantScope.getCodeShrinker() == CodeShrinker.PROGUARD;

            // If ProGuard will be used, we'll end up with a "fat" jar anyway. If we're using the
            // new dexing pipeline, we'll use the new MainDexListTransform below, so there's no need
            // for merging all classes into a single jar.
            if (!proguardInPipeline && !usingIncrementalDexing(variantScope)) {
                // Create a transform to jar the inputs into a single jar. Merge the classes only,
                // no need to package the resources since they are not used during the computation.
                JarMergingTransform jarMergingTransform =
                        new JarMergingTransform(TransformManager.SCOPE_FULL_PROJECT);
                transformManager
                        .addTransform(taskFactory, variantScope, jarMergingTransform)
                        .ifPresent(variantScope::addColdSwapBuildTask);
            }
        }

        if (variantScope.getNeedsMainDexList()) {

            // ---------
            // create the transform that's going to take the code and the proguard keep list
            // from above and compute the main class list.
            Transform multiDexTransform;

            if (usingIncrementalDexing(variantScope)) {
                if (projectOptions.get(BooleanOption.ENABLE_D8_MAIN_DEX_LIST)) {
                    multiDexTransform = new D8MainDexListTransform(variantScope);
                } else {
                    multiDexTransform =
                            new MainDexListTransform(variantScope, extension.getDexOptions());
                }
            } else {
                // This legacy codepath cannot be used without merging all the
                // classes first. We can't fail during configuration for the bundle tool, but we
                // should fail with a clear error message during execution.
                multiDexTransform =
                        new MultiDexTransform(
                                variantScope,
                                extension.getDexOptions(),
                                dexingType == DexingType.LEGACY_MULTIDEX);
            }
            //TODO 打log发现是D8MainDexListTransform
            System.out.println("multiDexTransfrom class:" + multiDexTransform.getClass().getName());

            transformManager
                    .addTransform(taskFactory, variantScope, multiDexTransform)
                    .ifPresent(
                            task -> {
                                variantScope.addColdSwapBuildTask(task);
                                File mainDexListFile =
                                        variantScope
                                                .getArtifacts()
                                                .appendArtifact(
                                                        InternalArtifactType
                                                                .LEGACY_MULTIDEX_MAIN_DEX_LIST,
                                                        task,
                                                        "mainDexList.txt");
                                ((MainDexListWriter) multiDexTransform)
                                        .setMainDexListOutputFile(mainDexListFile);
                            });
        }

        if (variantScope.getNeedsMainDexListForBundle()) {
            D8MainDexListTransform bundleMultiDexTransform =
                    new D8MainDexListTransform(variantScope, true);
            variantScope
                    .getTransformManager()
                    .addTransform(taskFactory, variantScope, bundleMultiDexTransform)
                    .ifPresent(
                            task -> {
                                File mainDexListFile =
                                        variantScope
                                                .getArtifacts()
                                                .appendArtifact(
                                                        InternalArtifactType
                                                                .MAIN_DEX_LIST_FOR_BUNDLE,
                                                        task,
                                                        "mainDexList.txt");
                                bundleMultiDexTransform.setMainDexListOutputFile(mainDexListFile);
                            });
        }

        if (usingIncrementalDexing(variantScope)) {
            createNewDexTasks(variantScope, dexingType);
        } else {
            createDexTasks(variantScope, dexingType);
        }

        if (preColdSwapTask != null) {
            for (DefaultTask task : variantScope.getColdSwapBuildTasks()) {
                task.dependsOn(preColdSwapTask);
            }
        }
        maybeCreateResourcesShrinkerTransform(variantScope);

        // TODO: support DexSplitterTransform when IR enabled (http://b/77585545)
        maybeCreateDexSplitterTransform(variantScope);
        // TODO: create JavaResSplitterTransform and call it here (http://b/77546738)
    }

    private boolean runJavaCodeShrinker(VariantScope variantScope) {
        return variantScope.getCodeShrinker() != null || isTestedAppObfuscated(variantScope);
    }

    /**
     * Creates the pre-dexing task if needed, and task for producing the final DEX file(s).
     */
    private void createDexTasks(
            @NonNull VariantScope variantScope,
            @NonNull DexingType dexingType) {
        TransformManager transformManager = variantScope.getTransformManager();
        AndroidBuilder androidBuilder = variantScope.getGlobalScope().getAndroidBuilder();

        DefaultDexOptions dexOptions;
        if (variantScope.getVariantData().getType().isTestComponent()) {
            // Don't use custom dx flags when compiling the test FULL_APK. They can break the test FULL_APK,
            // like --minimal-main-dex.
            dexOptions = DefaultDexOptions.copyOf(extension.getDexOptions());
            dexOptions.setAdditionalParameters(ImmutableList.of());
        } else {
            dexOptions = extension.getDexOptions();
        }

        boolean cachePreDex =
                dexingType.isPreDex()
                        && dexOptions.getPreDexLibraries()
                        && !runJavaCodeShrinker(variantScope);
        boolean preDexEnabled =
                variantScope.getInstantRunBuildContext().isInInstantRunMode() || cachePreDex;
        if (preDexEnabled) {
            FileCache buildCache;
            if (cachePreDex
                    && projectOptions.get(BooleanOption.ENABLE_INTERMEDIATE_ARTIFACTS_CACHE)) {

                //TODO 这个要通过反射来获取
                //buildCache = this.buildCache;
                buildCache = globalScope.getBuildCache();
            } else {
                buildCache = null;
            }

            PreDexTransform preDexTransform =
                    new PreDexTransform(
                            dexOptions,
                            androidBuilder,
                            buildCache,
                            dexingType,
                            variantScope.getMinSdkVersion().getFeatureLevel(),
                            variantScope.consumesFeatureJars());
            transformManager
                    .addTransform(taskFactory, variantScope, preDexTransform)
                    .ifPresent(variantScope::addColdSwapBuildTask);
        }

        if (!preDexEnabled || dexingType != DexingType.NATIVE_MULTIDEX) {
            // run if non native multidex or no pre-dexing
            DexTransform dexTransform =
                    new DexTransform(
                            dexOptions,
                            dexingType,
                            preDexEnabled,
                            dexingType == DexingType.LEGACY_MULTIDEX
                                    ? variantScope
                                    .getArtifacts()
                                    .getFinalArtifactFiles(LEGACY_MULTIDEX_MAIN_DEX_LIST)
                                    : null,
                            checkNotNull(androidBuilder.getTargetInfo(), "Target Info not set."),
                            androidBuilder.getDexByteCodeConverter(),
                            variantScope.getGlobalScope().getMessageReceiver(),
                            variantScope.getMinSdkVersion().getFeatureLevel(),
                            variantScope.consumesFeatureJars());
            Optional<TransformTask> dexTask =
                    transformManager.addTransform(taskFactory, variantScope, dexTransform);
            // need to manually make dex task depend on MultiDexTransform since there's no stream
            // consumption making this automatic
            dexTask.ifPresent(variantScope::addColdSwapBuildTask);
        }
    }

    @NonNull
    private static List<String> getAdvancedProfilingTransforms(@NonNull ProjectOptions options) {
        String string = options.get(StringOption.IDE_ANDROID_CUSTOM_CLASS_TRANSFORMS);
        if (string == null) {
            return ImmutableList.of();
        }
        return Splitter.on(',').splitToList(string);
    }

    /**
     * Create InstantRun related tasks that should be ran right after the java compilation task.
     */
    @NonNull
    private DefaultTask createInstantRunAllActionsTasks(@NonNull VariantScope variantScope) {

        DefaultTask allActionAnchorTask =
                taskFactory.create(new InstantRunAnchorTaskConfigAction(variantScope));

        TransformManager transformManager = variantScope.getTransformManager();

        ExtractJarsTransform extractJarsTransform =
                new ExtractJarsTransform(
                        ImmutableSet.of(QualifiedContent.DefaultContentType.CLASSES),
                        ImmutableSet.of(QualifiedContent.Scope.SUB_PROJECTS));
        Optional<TransformTask> extractJarsTask =
                transformManager.addTransform(taskFactory, variantScope, extractJarsTransform);

        InstantRunTaskManager instantRunTaskManager =
                new InstantRunTaskManager(
                        getLogger(),
                        variantScope,
                        variantScope.getTransformManager(),
                        taskFactory,
                        recorder);

        BuildableArtifact instantRunMergedManifests =
                variantScope.getArtifacts().getFinalArtifactFiles(INSTANT_RUN_MERGED_MANIFESTS);

        variantScope.setInstantRunTaskManager(instantRunTaskManager);
        AndroidVersion minSdkForDx = variantScope.getMinSdkVersion();
        BuildInfoLoaderTask buildInfoLoaderTask =
                instantRunTaskManager.createInstantRunAllTasks(
                        extractJarsTask.orElse(null),
                        allActionAnchorTask,
                        getResMergingScopes(variantScope),
                        instantRunMergedManifests,
                        true /* addResourceVerifier */,
                        minSdkForDx.getFeatureLevel(),
                        variantScope.getJava8LangSupportType() == VariantScope.Java8LangSupport.D8,
                        variantScope.getBootClasspath(),
                        globalScope.getAndroidBuilder().getMessageReceiver());

        if (variantScope.getTaskContainer().getSourceGenTask() != null) {
            variantScope.getTaskContainer().getSourceGenTask().dependsOn(buildInfoLoaderTask);
        }

        return allActionAnchorTask;
    }

    private void maybeCreateDexSplitterTransform(@NonNull VariantScope variantScope) {
        if (!variantScope.consumesFeatureJars()) {
            return;
        }

        File dexSplitterOutput =
                FileUtils.join(
                        globalScope.getIntermediatesDir(),
                        "dex-splitter",
                        variantScope.getVariantConfiguration().getDirName());
        FileCollection featureJars =
                variantScope.getArtifactFileCollection(METADATA_VALUES, MODULE, METADATA_CLASSES);
        BuildableArtifact mappingFileSrc =
                variantScope.getArtifacts().hasArtifact(InternalArtifactType.APK_MAPPING)
                        ? variantScope
                        .getArtifacts()
                        .getFinalArtifactFiles(InternalArtifactType.APK_MAPPING)
                        : null;

        DexSplitterTransform transform =
                new DexSplitterTransform(dexSplitterOutput, featureJars, mappingFileSrc);

        Optional<TransformTask> transformTask =
                variantScope
                        .getTransformManager()
                        .addTransform(taskFactory, variantScope, transform);

        if (transformTask.isPresent()) {
            variantScope
                    .getArtifacts()
                    .appendArtifact(
                            InternalArtifactType.FEATURE_DEX,
                            ImmutableList.of(dexSplitterOutput),
                            transformTask.get());
            publishFeatureDex(variantScope);
        } else {
            globalScope
                    .getAndroidBuilder()
                    .getIssueReporter()
                    .reportError(
                            EvalIssueReporter.Type.GENERIC,
                            new EvalIssueException(
                                    "Internal error, could not add the DexSplitterTransform"));
        }
    }


    /**
     * We have a separate method for publishing the classes.dex files back to the features (instead
     * of using the typical PublishingSpecs pipeline) because multiple artifacts are published per
     * BuildableArtifact in this case.
     *
     * <p>This method is similar to VariantScopeImpl.publishIntermediateArtifact, and some of the
     * code was pulled from there. Once there's support for publishing multiple artifacts per
     * BuildableArtifact in the PublishingSpecs pipeline, we can get rid of this method.
     */
    private void publishFeatureDex(@NonNull VariantScope variantScope) {
        // first calculate the list of module paths
        final Collection<String> modulePaths;
        final AndroidConfig extension = globalScope.getExtension();
        if (extension instanceof BaseAppModuleExtension) {
            modulePaths = ((BaseAppModuleExtension) extension).getDynamicFeatures();
        } else if (extension instanceof FeatureExtension) {
            modulePaths = FeatureModelBuilder.getDynamicFeatures(globalScope);
        } else {
            return;
        }

        Configuration configuration =
                variantScope.getVariantData().getVariantDependency().getRuntimeElements();
        Preconditions.checkNotNull(
                configuration,
                "Publishing to Runtime Element with no Runtime Elements configuration object. "
                        + "VariantType: "
                        + variantScope.getType());
        BuildableArtifact artifact =
                variantScope.getArtifacts().getFinalArtifactFiles(InternalArtifactType.FEATURE_DEX);
        for (String modulePath : modulePaths) {
            final String absoluteModulePath = project.absoluteProjectPath(modulePath);
            Provider<File> file =
                    project.provider(
                            () ->
                                    new File(
                                            Iterables.getOnlyElement(artifact.getFiles()),
                                            "features" + absoluteModulePath.replace(":", "/")));
            Map<Attribute<String>, String> attributeMap =
                    ImmutableMap.of(MODULE_PATH, absoluteModulePath);
            publishArtifactToConfiguration(
                    configuration,
                    file,
                    artifact,
                    AndroidArtifacts.ArtifactType.FEATURE_DEX,
                    attributeMap);
        }
    }

    private void createMergeClassesTransform(@NonNull VariantScope variantScope) {

        File outputJar =
                FileUtils.join(
                        globalScope.getIntermediatesDir(),
                        "merged-classes",
                        variantScope.getVariantConfiguration().getDirName(),
                        SdkConstants.FN_CLASSES_JAR);

        MergeClassesTransform transform =
                new MergeClassesTransform(outputJar, globalScope.getProject().getPath());

        Optional<TransformTask> transformTask =
                variantScope
                        .getTransformManager()
                        .addTransform(taskFactory, variantScope, transform);

        if (transformTask.isPresent()) {
            variantScope
                    .getArtifacts()
                    .appendArtifact(
                            InternalArtifactType.FEATURE_AND_RUNTIME_DEPS_CLASSES,
                            ImmutableList.of(outputJar),
                            transformTask.get());
        } else {
            globalScope
                    .getAndroidBuilder()
                    .getIssueReporter()
                    .reportError(
                            EvalIssueReporter.Type.GENERIC,
                            new EvalIssueException(
                                    "Internal error, could not add the MergeClassesTransform"));
        }
    }

    private void maybeCreateDesugarTask(
            @NonNull VariantScope variantScope,
            @NonNull AndroidVersion minSdk,
            @NonNull TransformManager transformManager) {
        if (variantScope.getJava8LangSupportType() == VariantScope.Java8LangSupport.DESUGAR) {
            FileCache userCache = getUserIntermediatesCache();

            FixStackFramesTransform fixFrames =
                    new FixStackFramesTransform(variantScope.getBootClasspath(), userCache);
            transformManager.addTransform(taskFactory, variantScope, fixFrames);

            DesugarTransform desugarTransform =
                    new DesugarTransform(
                            variantScope.getBootClasspath(),
                            userCache,
                            minSdk.getFeatureLevel(),
                            globalScope.getAndroidBuilder().getJavaProcessExecutor(),
                            project.getLogger().isEnabled(LogLevel.INFO),
                            projectOptions.get(BooleanOption.ENABLE_GRADLE_WORKERS),
                            variantScope.getGlobalScope().getTmpFolder().toPath(),
                            getProjectVariantId(variantScope),
                            projectOptions.get(BooleanOption.ENABLE_INCREMENTAL_DESUGARING),
                            enableDesugarBugFixForJacoco(variantScope));
            transformManager.addTransform(taskFactory, variantScope, desugarTransform);

            if (minSdk.getFeatureLevel()
                    >= DesugarProcessArgs.MIN_SUPPORTED_API_TRY_WITH_RESOURCES) {
                return;
            }

            if (variantScope.getVariantConfiguration().getType().isTestComponent()) {
                BaseVariantData testedVariant =
                        Objects.requireNonNull(variantScope.getTestedVariantData());
                if (!testedVariant.getType().isAar()) {
                    // test variants, except for library, should not package try-with-resources jar
                    // as the tested variant already contains it
                    return;
                }
            }

            // add runtime classes for try-with-resources support
            String taskName = variantScope.getTaskName(ExtractTryWithResourcesSupportJar.TASK_NAME);
            ExtractTryWithResourcesSupportJar extractTryWithResources =
                    taskFactory.create(
                            new ExtractTryWithResourcesSupportJar.ConfigAction(
                                    variantScope.getTryWithResourceRuntimeSupportJar(),
                                    taskName,
                                    variantScope.getFullVariantName()));
            variantScope.getTryWithResourceRuntimeSupportJar().builtBy(extractTryWithResources);
            transformManager.addStream(
                    OriginalStream.builder(project, "runtime-deps-try-with-resources")
                            .addContentTypes(TransformManager.CONTENT_CLASS)
                            .addScope(QualifiedContent.Scope.EXTERNAL_LIBRARIES)
                            .setFileCollection(variantScope.getTryWithResourceRuntimeSupportJar())
                            .build());
        }
    }

    @Nullable
    private FileCache getUserIntermediatesCache() {
        if (globalScope
                .getProjectOptions()
                .get(BooleanOption.ENABLE_INTERMEDIATE_ARTIFACTS_CACHE)) {
            return globalScope.getBuildCache();
        } else {
            return null;
        }
    }

    @NonNull
    private static String getProjectVariantId(@NonNull VariantScope variantScope) {
        return variantScope.getGlobalScope().getProject().getName()
                + ":"
                + variantScope.getFullVariantName();
    }

    private boolean usingIncrementalDexing(@NonNull VariantScope variantScope) {
        if (!projectOptions.get(BooleanOption.ENABLE_DEX_ARCHIVE)) {
            return false;
        }
        if (variantScope.getVariantConfiguration().getBuildType().isDebuggable()) {
            return true;
        }

        // In release builds only D8 can be used. See b/37140568 for details.
        return projectOptions.get(BooleanOption.ENABLE_D8);
    }

    /**
     * Creates tasks used for DEX generation. This will use a new pipeline that uses dex archives in
     * order to enable incremental dexing support.
     */
    private void createNewDexTasks(
            @NonNull VariantScope variantScope,
            @NonNull DexingType dexingType) {
        TransformManager transformManager = variantScope.getTransformManager();

        DefaultDexOptions dexOptions;
        if (variantScope.getVariantData().getType().isTestComponent()) {
            // Don't use custom dx flags when compiling the test FULL_APK. They can break the test FULL_APK,
            // like --minimal-main-dex.
            dexOptions = DefaultDexOptions.copyOf(extension.getDexOptions());
            dexOptions.setAdditionalParameters(ImmutableList.of());
        } else {
            dexOptions = extension.getDexOptions();
        }

        boolean minified = runJavaCodeShrinker(variantScope);
        FileCache userLevelCache = getUserDexCache(minified, dexOptions.getPreDexLibraries());
        DexArchiveBuilderTransform preDexTransform =
                new DexArchiveBuilderTransformBuilder()
                        .setAndroidJarClasspath(
                                () ->
                                        variantScope
                                                .getGlobalScope()
                                                .getAndroidBuilder()
                                                .getBootClasspath(false))
                        .setDexOptions(dexOptions)
                        .setMessageReceiver(variantScope.getGlobalScope().getMessageReceiver())
                        .setUserLevelCache(userLevelCache)
                        .setMinSdkVersion(variantScope.getMinSdkVersion().getFeatureLevel())
                        .setDexer(variantScope.getDexer())
                        .setUseGradleWorkers(
                                projectOptions.get(BooleanOption.ENABLE_GRADLE_WORKERS))
                        .setInBufferSize(projectOptions.get(IntegerOption.DEXING_READ_BUFFER_SIZE))
                        .setOutBufferSize(
                                projectOptions.get(IntegerOption.DEXING_WRITE_BUFFER_SIZE))
                        .setIsDebuggable(
                                variantScope
                                        .getVariantConfiguration()
                                        .getBuildType()
                                        .isDebuggable())
                        .setJava8LangSupportType(variantScope.getJava8LangSupportType())
                        .setEnableIncrementalDesugaring(
                                projectOptions.get(BooleanOption.ENABLE_INCREMENTAL_DESUGARING))
                        .setProjectVariant(getProjectVariantId(variantScope))
                        .setNumberOfBuckets(
                                projectOptions.get(IntegerOption.DEXING_NUMBER_OF_BUCKETS))
                        .setIncludeFeaturesInScope(variantScope.consumesFeatureJars())
                        .setIsInstantRun(
                                variantScope.getInstantRunBuildContext().isInInstantRunMode())
                        .createDexArchiveBuilderTransform();
        transformManager
                .addTransform(taskFactory, variantScope, preDexTransform)
                .ifPresent(variantScope::addColdSwapBuildTask);

        boolean isDebuggable = variantScope.getVariantConfiguration().getBuildType().isDebuggable();
        if (dexingType != DexingType.LEGACY_MULTIDEX
                && variantScope.getCodeShrinker() == null
                && extension.getTransforms().isEmpty()) {
            ExternalLibsMergerTransform externalLibsMergerTransform =
                    new ExternalLibsMergerTransform(
                            dexingType,
                            variantScope.getDexMerger(),
                            variantScope.getMinSdkVersion().getFeatureLevel(),
                            isDebuggable,
                            variantScope.getGlobalScope().getMessageReceiver(),
                            DexMergerTransformCallable::new);

            transformManager.addTransform(taskFactory, variantScope, externalLibsMergerTransform);
        }

        DexMergerTransform dexTransform =
                new DexMergerTransform(
                        dexingType,
                        dexingType == DexingType.LEGACY_MULTIDEX
                                ? variantScope
                                .getArtifacts()
                                .getFinalArtifactFiles(
                                        InternalArtifactType.LEGACY_MULTIDEX_MAIN_DEX_LIST)
                                : null,
                        variantScope.getGlobalScope().getMessageReceiver(),
                        variantScope.getDexMerger(),
                        variantScope.getMinSdkVersion().getFeatureLevel(),
                        isDebuggable,
                        variantScope.consumesFeatureJars(),
                        variantScope.getInstantRunBuildContext().isInInstantRunMode());
        Optional<TransformTask> dexTask =
                transformManager.addTransform(taskFactory, variantScope, dexTransform);
        // need to manually make dex task depend on MultiDexTransform since there's no stream
        // consumption making this automatic
        dexTask.ifPresent(variantScope::addColdSwapBuildTask);
    }

    /**
     * If a fix in Desugar should be enabled to handle broken bytecode produced by older Jacoco, see
     * http://b/62623509.
     */
    private boolean enableDesugarBugFixForJacoco(@NonNull VariantScope scope) {
        try {
            GradleVersion current = GradleVersion.parse(getJacocoVersion(scope));
            return JacocoConfigurations.MIN_WITHOUT_BROKEN_BYTECODE.compareTo(current) > 0;
        } catch (Throwable ignored) {
            // Cannot determine using version comparison, avoid passing the flag.
            return true;
        }
    }

    @Nullable
    private FileCache getUserDexCache(boolean isMinifiedEnabled, boolean preDexLibraries) {
        if (!preDexLibraries || isMinifiedEnabled) {
            return null;
        }

        return getUserIntermediatesCache();
    }

}
