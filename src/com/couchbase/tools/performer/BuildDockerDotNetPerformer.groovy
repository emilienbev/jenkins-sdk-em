package com.couchbase.tools.performer

import com.couchbase.context.environments.Environment
import com.couchbase.tools.tags.TagProcessor
import com.couchbase.versions.ImplementationVersion
import groovy.transform.CompileStatic
import java.util.regex.Pattern

@CompileStatic
class BuildDockerDotNetPerformer {
    /**
     * @param path absolute path to above 'transactions-fit-performer'
     * @param build what to build
     */
    static void build(Environment imp, String path, VersionToBuild build, String imageName, boolean onlySource = false) {
        imp.log("Building .NET ${build}")

        // Build context needs to be perf-sdk as we need the .proto files
        imp.dirAbsolute(path) {
            imp.dir('transactions-fit-performer') {
                //No need to use the submodule as the Dockerfile deletes it and runs a fresh clone
                imp.dir('performers/dotnet/Couchbase.Transactions.FitPerformer') {
                    // couchbase-net-client is a git submodule
                    TagProcessor.processTags(new File(imp.currentDir()), build, Optional.of(Pattern.compile(".*\\.cs")))
                }
            }
            if (!onlySource) {
                var dotnetVersion = "10.0"
                if (build instanceof HasVersion && build.implementationVersion().isBelow(ImplementationVersion.from("3.8.2"))){
                    dotnetVersion = "8.0"
                }
                else if (build instanceof HasVersion && build.implementationVersion().isBelow(ImplementationVersion.from("3.4.14"))){
                    dotnetVersion = "6.0"
                }

                if (build instanceof BuildMain) {
                    imp.execute("docker build -f transactions-fit-performer/performers/dotnet/Couchbase.Transactions.FitPerformer/Dockerfile -t $imageName --build-arg FIT_DOTNET_VERSION=$dotnetVersion .", false, true, true)
                }
                else if (build instanceof BuildGerrit) {
                    imp.execute("docker build -f transactions-fit-performer/performers/dotnet/Couchbase.Transactions.FitPerformer/Dockerfile -t $imageName --build-arg FIT_DOTNET_VERSION=$dotnetVersion --build-arg BUILD_GERRIT=${build.gerrit()} .", false, true, true)
                }
                else if (build instanceof HasSha) {
                    imp.execute("docker build -f transactions-fit-performer/performers/dotnet/Couchbase.Transactions.FitPerformer/Dockerfile -t $imageName --build-arg FIT_DOTNET_VERSION=$dotnetVersion --build-arg SDK_BRANCH=${build.sha()} .", false, true, true)
                }
                else if (build instanceof HasVersion) {
                    imp.execute("docker build -f transactions-fit-performer/performers/dotnet/Couchbase.Transactions.FitPerformer/Dockerfile -t $imageName --build-arg FIT_DOTNET_VERSION=$dotnetVersion --build-arg SDK_BRANCH=tags/${build.version()} .", false, true, true)
                }
            }
        }
    }
}
