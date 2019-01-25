package org.qiyi.arch.app;

import com.android.annotations.NonNull;
//import com.android.build.gradle.AbstractAppPlugin;
import com.android.build.gradle.AndroidConfig;
import com.android.build.gradle.AppExtension;
import com.android.build.gradle.internal.AppModelBuilder;
import com.android.build.gradle.internal.ExtraModelInfo;
import com.android.build.gradle.internal.NativeLibraryFactoryImpl;
import com.android.build.gradle.internal.VariantManager;
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension;
import com.android.build.gradle.internal.errors.DeprecationReporter;
import com.android.build.gradle.internal.scope.GlobalScope;
import com.android.builder.model.AndroidProject;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry;

import javax.inject.Inject;

/**
 * author: AllenWang
 * date: 2019/1/24
 */
public class QigsawAppPlugin extends AbstractAppPlugin {

    @Inject
    public QigsawAppPlugin(ToolingModelBuilderRegistry registry) {
        super(registry, true /*isBaseApplication*/);
    }

    @Override
    public void apply(@NonNull Project project) {
        super.apply(project);

        // root bundle task
        taskManager.getTaskFactory().create("bundle");
    }

    @Override
    protected void registerModelBuilder(
            @NonNull ToolingModelBuilderRegistry registry,
            @NonNull GlobalScope globalScope,
            @NonNull VariantManager variantManager,
            @NonNull AndroidConfig config,
            @NonNull ExtraModelInfo extraModelInfo) {
        registry.register(
                new AppModelBuilder(
                        globalScope,
                        variantManager,
                        taskManager,
                        (BaseAppModuleExtension) config,
                        extraModelInfo,
                        new NativeLibraryFactoryImpl(globalScope.getNdkHandler()),
                        getProjectType(),
                        AndroidProject.GENERATION_ORIGINAL));
    }

    @Override
    @NonNull
    protected Class<? extends AppExtension> getExtensionClass() {
        return BaseAppModuleExtension.class;
    }

    private static class DeprecatedConfigurationAction implements Action<Dependency> {
        @NonNull private final String newDslElement;
        @NonNull private final String configName;
        @NonNull private final DeprecationReporter deprecationReporter;
        @NonNull private final DeprecationReporter.DeprecationTarget target;
        private boolean warningPrintedAlready = false;

        public DeprecatedConfigurationAction(
                @NonNull String newDslElement,
                @NonNull String configName,
                @NonNull DeprecationReporter deprecationReporter,
                @NonNull DeprecationReporter.DeprecationTarget target) {
            this.newDslElement = newDslElement;
            this.configName = configName;
            this.deprecationReporter = deprecationReporter;
            this.target = target;
        }

        @Override
        public void execute(@NonNull Dependency dependency) {
            if (!warningPrintedAlready) {
                warningPrintedAlready = true;
                deprecationReporter.reportDeprecatedConfiguration(
                        newDslElement, configName, target);
            }
        }
    }
}
