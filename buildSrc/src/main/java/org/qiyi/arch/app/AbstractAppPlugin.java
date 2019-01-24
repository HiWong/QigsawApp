package org.qiyi.arch.app;

import android.databinding.tool.DataBindingBuilder;

import com.android.annotations.NonNull;
import com.android.build.gradle.AndroidConfig;
import com.android.build.gradle.internal.ApplicationTaskManager;
import com.android.build.gradle.internal.SdkHandler;
import com.android.build.gradle.internal.TaskManager;
import com.android.build.gradle.internal.api.dsl.extensions.AppExtensionImpl;
import com.android.build.gradle.internal.plugin.AppPluginDelegate;
import com.android.build.gradle.internal.plugin.TypedPluginDelegate;
import com.android.build.gradle.internal.scope.GlobalScope;
import com.android.build.gradle.internal.variant.ApplicationVariantFactory;
import com.android.build.gradle.options.ProjectOptions;
import com.android.builder.profile.Recorder;

import org.gradle.api.Project;
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry;

import javax.inject.Inject;

/**
 * author: AllenWang
 * date: 2019/1/24
 */
public abstract class AbstractAppPlugin extends com.android.build.gradle.AbstractAppPlugin {


    @Inject
    public AbstractAppPlugin(ToolingModelBuilderRegistry registry, boolean isBaseApplication) {
        super(registry, isBaseApplication);
    }

    @NonNull
    @Override
    protected TaskManager createTaskManager(
            @NonNull GlobalScope globalScope,
            @NonNull Project project,
            @NonNull ProjectOptions projectOptions,
            @NonNull DataBindingBuilder dataBindingBuilder,
            @NonNull AndroidConfig androidConfig,
            @NonNull SdkHandler sdkHandler,
            @NonNull ToolingModelBuilderRegistry toolingRegistry,
            @NonNull Recorder recorder) {
        return new ApplicationTaskManager(
                globalScope,
                project,
                projectOptions,
                dataBindingBuilder,
                androidConfig,
                sdkHandler,
                toolingRegistry,
                recorder);
    }

    @Override
    public void apply(@NonNull Project project) {
        super.apply(project);
    }

    @NonNull
    @Override
    protected ApplicationVariantFactory createVariantFactory(
            @NonNull GlobalScope globalScope,
            @NonNull AndroidConfig androidConfig) {
        return new ApplicationVariantFactory(globalScope, androidConfig);
    }

    @Override
    protected TypedPluginDelegate<AppExtensionImpl> getTypedDelegate() {
        return new AppPluginDelegate();
    }
}
