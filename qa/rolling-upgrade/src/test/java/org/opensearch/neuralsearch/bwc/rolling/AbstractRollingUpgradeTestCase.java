/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.bwc.rolling;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.junit.Before;
import org.opensearch.common.settings.Settings;
import org.opensearch.ml.common.model.MLModelState;
import org.opensearch.neuralsearch.BaseNeuralSearchIT;
import static org.opensearch.neuralsearch.util.TestUtils.NEURAL_SEARCH_BWC_PREFIX;
import static org.opensearch.neuralsearch.util.TestUtils.OLD_CLUSTER;
import static org.opensearch.neuralsearch.util.TestUtils.MIXED_CLUSTER;
import static org.opensearch.neuralsearch.util.TestUtils.UPGRADED_CLUSTER;
import static org.opensearch.neuralsearch.util.TestUtils.ROLLING_UPGRADE_FIRST_ROUND;
import static org.opensearch.neuralsearch.util.TestUtils.BWCSUITE_CLUSTER;
import static org.opensearch.neuralsearch.util.TestUtils.BWC_VERSION;
import static org.opensearch.neuralsearch.util.TestUtils.generateModelId;
import org.opensearch.test.rest.OpenSearchRestTestCase;

public abstract class AbstractRollingUpgradeTestCase extends BaseNeuralSearchIT {

    private static final Set<MLModelState> READY_FOR_INFERENCE_STATES = Set.of(MLModelState.LOADED, MLModelState.DEPLOYED);

    @Before
    protected String getIndexNameForTest() {
        // Creating index name by concatenating "neural-bwc-" prefix with test method name
        // for all the tests in this sub-project
        return NEURAL_SEARCH_BWC_PREFIX + getTestName().toLowerCase(Locale.ROOT);
    }

    @Override
    protected final boolean preserveIndicesUponCompletion() {
        return true;
    }

    @Override
    protected final boolean preserveReposUponCompletion() {
        return true;
    }

    @Override
    protected boolean preserveTemplatesUponCompletion() {
        return true;
    }

    @Override
    protected final Settings restClientSettings() {
        return Settings.builder()
            .put(super.restClientSettings())
            // increase the timeout here to 90 seconds to handle long waits for a green
            // cluster health. the waits for green need to be longer than a minute to
            // account for delayed shards
            .put(OpenSearchRestTestCase.CLIENT_SOCKET_TIMEOUT, "120s")
            .build();
    }

    @Override
    protected boolean shouldCleanUpResources() {
        // All UPGRADE tests depend on resources created in OLD and MIXED test cases
        // Before UPGRADE tests run, all OLD and MIXED test cases will be run first
        // We only want to clean up resources in upgrade tests, also we don't want to clean up after each test case finishes
        // this is because the cleanup method will pull every resource and delete, which will impact other tests
        // Overriding the method in base class so that resources won't be accidentally clean up
        return false;
    }

    protected enum ClusterType {
        OLD,
        MIXED,
        UPGRADED;

        public static ClusterType instance(String value) {
            switch (value) {
                case OLD_CLUSTER:
                    return OLD;
                case MIXED_CLUSTER:
                    return MIXED;
                case UPGRADED_CLUSTER:
                    return UPGRADED;
                default:
                    throw new IllegalArgumentException("unknown cluster type: " + value);
            }
        }
    }

    protected final ClusterType getClusterType() {
        return ClusterType.instance(System.getProperty(BWCSUITE_CLUSTER));
    }

    protected final boolean isFirstMixedRound() {
        return Boolean.parseBoolean(System.getProperty(ROLLING_UPGRADE_FIRST_ROUND, "false"));
    }

    protected final Optional<String> getBWCVersion() {
        return Optional.ofNullable(System.getProperty(BWC_VERSION, null));
    }

    protected String uploadTextEmbeddingModel() throws Exception {
        String requestBody = Files.readString(Path.of(classLoader.getResource("processor/UploadModelRequestBody.json").toURI()));
        return registerModelGroupAndGetModelId(requestBody);
    }

    protected String registerModelGroupAndGetModelId(String requestBody) throws Exception {
        String modelGroupRegisterRequestBody = Files.readString(
            Path.of(classLoader.getResource("processor/CreateModelGroupRequestBody.json").toURI())
        );
        String modelGroupId = registerModelGroup(String.format(LOCALE, modelGroupRegisterRequestBody, generateModelId()));
        return uploadModel(String.format(LOCALE, requestBody, modelGroupId));
    }

    protected void createPipelineProcessor(String modelId, String pipelineName) throws Exception {
        String requestBody = Files.readString(Path.of(classLoader.getResource("processor/PipelineConfiguration.json").toURI()));
        createPipelineProcessor(requestBody, pipelineName, modelId, null);
    }

    protected String uploadTextImageEmbeddingModel() throws Exception {
        String requestBody = Files.readString(Path.of(classLoader.getResource("processor/UploadModelRequestBody.json").toURI()));
        return registerModelGroupAndGetModelId(requestBody);
    }

    protected void createPipelineForTextImageProcessor(String modelId, String pipelineName) throws Exception {
        String requestBody = Files.readString(
            Path.of(classLoader.getResource("processor/PipelineForTextImageProcessorConfiguration.json").toURI())
        );
        createPipelineProcessor(requestBody, pipelineName, modelId, null);
    }

    protected String uploadSparseEncodingModel() throws Exception {
        String requestBody = Files.readString(
            Path.of(classLoader.getResource("processor/UploadSparseEncodingModelRequestBody.json").toURI())
        );
        return registerModelGroupAndGetModelId(requestBody);
    }

    protected void createPipelineForSparseEncodingProcessor(String modelId, String pipelineName, Integer batchSize) throws Exception {
        String requestBody = Files.readString(
            Path.of(classLoader.getResource("processor/PipelineForSparseEncodingProcessorConfiguration.json").toURI())
        );
        final String batchSizeTag = "{{batch_size}}";
        if (requestBody.contains(batchSizeTag)) {
            if (batchSize != null) {
                requestBody = requestBody.replace(batchSizeTag, String.format(LOCALE, "\n\"batch_size\": %d,\n", batchSize));
            } else {
                requestBody = requestBody.replace(batchSizeTag, "");
            }
        }
        createPipelineProcessor(requestBody, pipelineName, modelId, null);
    }

    protected void createPipelineForSparseEncodingProcessor(String modelId, String pipelineName) throws Exception {
        createPipelineForSparseEncodingProcessor(modelId, pipelineName, null);
    }

    protected void createPipelineForTextChunkingProcessor(String pipelineName) throws Exception {
        String requestBody = Files.readString(
            Path.of(classLoader.getResource("processor/PipelineForTextChunkingProcessorConfiguration.json").toURI())
        );
        createPipelineProcessor(requestBody, pipelineName, "", null);
    }

    protected boolean isModelReadyForInference(final MLModelState mlModelState) throws Exception {
        return READY_FOR_INFERENCE_STATES.contains(mlModelState);
    }

    protected void waitForModelToLoad(String modelId) throws Exception {
        int maxAttempts = 30;  // Maximum number of attempts
        int waitTimeInSeconds = 2;  // Time to wait between attempts

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            MLModelState state = getModelState(modelId);
            if (isModelReadyForInference(state)) {
                logger.info("Model {} is now loaded after {} attempts", modelId, attempt + 1);
                return;
            }
            logger.info("Waiting for model {} to load. Current state: {}. Attempt {}/{}", modelId, state, attempt + 1, maxAttempts);
            Thread.sleep(waitTimeInSeconds * 1000);
        }
        throw new RuntimeException("Model " + modelId + " failed to load after " + maxAttempts + " attempts");
    }
}
