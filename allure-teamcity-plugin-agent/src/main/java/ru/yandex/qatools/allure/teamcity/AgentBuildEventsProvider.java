package ru.yandex.qatools.allure.teamcity;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.agent.*;
import jetbrains.buildServer.agent.artifacts.ArtifactsWatcher;
import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.util.EventDispatcher;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.jetbrains.annotations.NotNull;
import ru.yandex.qatools.allure.report.AllureReportBuilder;
import ru.yandex.qatools.allure.report.utils.AetherObjectFactory;
import ru.yandex.qatools.allure.report.utils.DependencyResolver;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static ru.yandex.qatools.allure.report.utils.AetherObjectFactory.newRemoteRepository;
import static ru.yandex.qatools.allure.report.utils.AetherObjectFactory.newRepositorySystem;
import static ru.yandex.qatools.allure.report.utils.AetherObjectFactory.newSession;

public class AgentBuildEventsProvider extends AgentLifeCycleAdapter {

    private static final Logger LOGGER = Loggers.AGENT;

    private final ArtifactsWatcher artifactsWatcher;

    public AgentBuildEventsProvider(@NotNull final EventDispatcher<AgentLifeCycleListener> dispatcher,
                                    @NotNull final ArtifactsWatcher artifactsWatcher) {
        this.artifactsWatcher = artifactsWatcher;
        dispatcher.addListener(this);
    }

    @Override
    public void buildStarted(@NotNull AgentRunningBuild runningBuild) {
        super.buildStarted(runningBuild);
        runningBuild.getBuildLogger();
    }

    @Override
    public void runnerFinished(@NotNull BuildRunnerContext runner, @NotNull BuildFinishedStatus status) {
        super.runnerFinished(runner, status);
        BuildProgressLogger logger = runner.getBuild().getBuildLogger();

        logger.message("Allure Report: report processing started");
        AgentRunningBuild runningBuild = runner.getBuild();
        AgentBuildFeature buildFeature = getAllureBuildFeature(runningBuild);
        if (buildFeature == null) {
            return;
        }

        if (BuildFinishedStatus.INTERRUPTED.equals(status)) {
            logger.message("Allure Report: Build was interrupted. Skipping Allure report generation.");
            return;
        }

        File checkoutDirectory = runner.getBuild().getCheckoutDirectory();
        String resultsMask[] = buildFeature.getParameters().get(Parameters.RESULTS_MASK).split(";");
        logger.message(String.format("Allure Report: analyse results mask %s", Arrays.toString(resultsMask)));

        File[] allureResultDirectoryList = FileUtils.findFilesByMask(checkoutDirectory, resultsMask);
        logger.message(String.format("Allure Report: analyse results directories %s",
                Arrays.toString(allureResultDirectoryList)));

        File allureReportDirectory = new File(checkoutDirectory, Parameters.RELATIVE_OUTPUT_DIRECTORY);

        try {
            String version = buildFeature.getParameters().get(Parameters.REPORT_VERSION);
            RepositorySystem repositorySystem = newRepositorySystem();
            RepositorySystemSession session = newSession(repositorySystem,
                    new File(runner.getWorkingDirectory(), ".m2/repository"));
            List<RemoteRepository> remotes = Arrays.asList(newRemoteRepository(AetherObjectFactory.MAVEN_CENTRAL_URL));
            DependencyResolver resolver = new DependencyResolver(repositorySystem, session, remotes);
            AllureReportBuilder builder = new AllureReportBuilder(version, allureReportDirectory, resolver);

            logger.message(String.format("Allure Report: process tests results to directory [%s]",
                    allureReportDirectory));
            builder.processResults(allureResultDirectoryList);
            logger.message(String.format("Allure Report: unpack report face to directory [%s]",
                    allureReportDirectory));
            builder.unpackFace();

            artifactsWatcher.addNewArtifactsPath(allureReportDirectory.getAbsolutePath());
        } catch (Exception e) {
            logger.exception(e);
        }
    }

    private AgentBuildFeature getAllureBuildFeature(final AgentRunningBuild runningBuild) {
        LOGGER.debug("Allure Report: checking whether Allure build feature is present.");
        for (final AgentBuildFeature buildFeature : runningBuild.getBuildFeatures()) {
            if (Parameters.BUILD_FEATURE_TYPE.equals(buildFeature.getType())) {
                LOGGER.debug("Allure Report: build feature is present. Will publish Allure artifacts.");
                return buildFeature;
            }
        }
        LOGGER.debug("Allure Report: build feature is not present. Will do nothing.");
        return null;
    }
}