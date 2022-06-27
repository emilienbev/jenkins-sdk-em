package com.couchbase.perf.sdk.stages


import com.couchbase.stages.Stage
import groovy.transform.CompileStatic
import com.couchbase.context.StageContext
import com.couchbase.perf.shared.config.PerfConfig

/**
 * Builds, copies (if needed), and runs a performer
 */
@CompileStatic
class InitialiseSDKPerformer extends Stage {
    public static final String CONTAINER_NAME = "performer"

    private PerfConfig.Implementation impl
    private int port = 8060
    private String imageName

    InitialiseSDKPerformer(PerfConfig.Implementation impl) {
        this.impl = impl
        if (impl.port != null) {
            port = impl.port
        }
    }

    @Override
    String name() {
        return "Init performer $impl"
    }

    @Override
    List<Stage> stagesPre(StageContext ctx) {
        if (impl.port != null) {
            // Nothing to do
            return []
        }
        else {
            if (impl.language == "java") {
                def stage1 = new BuildDockerJavaSDKPerformer(impl.version)
                imageName = stage1.imageName
                return produceStages(ctx, stage1, stage1.getImageName())
            } else if (impl.language == "go"){
                def stage1 = new BuildDockerGoSDKPerformer(impl.version)
                imageName = stage1.imageName
                return produceStages(ctx, stage1, stage1.getImageName())
            } else if (impl.language == "python"){
                def stage1 = new BuildDockerPythonSDKPerformer(impl.version)
                imageName = stage1.imageName
                return produceStages(ctx, stage1, stage1.getImageName())
            } else{
                throw new IllegalArgumentException("Unknown performer ${impl.language}")
            }
        }
    }

    @Override
    void executeImpl(StageContext ctx) {}

    int port() {
        return port
    }

    List<Stage> produceStages(StageContext ctx, Stage stage1, String imageName){
        List<Stage> stages = []

        if (!ctx.skipDockerBuild()) {
            stages.add(stage1)
        }

        if (ctx.performerServer == "localhost") {
            stages.add(new StartDockerImagePerformer(imageName, CONTAINER_NAME, port, impl.version))
        } else {
            throw new IllegalArgumentException("Cannot handle running on performer remote server")
        }

        return stages

    }
}