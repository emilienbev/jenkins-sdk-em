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
                Map<String, String> dockerBuildArgs = [:]

                var dotnetVersion = '10.0'
                if (build instanceof HasVersion && build.implementationVersion().isBelow(ImplementationVersion.from("3.8.2"))){
                    dotnetVersion = '8.0'
                }
                if (build instanceof HasVersion && build.implementationVersion().isBelow(ImplementationVersion.from("3.4.14"))){
                    dotnetVersion = '6.0'
                }
                dockerBuildArgs.put('FIT_DOTNET_VERSION', dotnetVersion)

                // The external Transactions project was removed between SDK 3.9.0 and 3.9.1, but was deprecated since 3.6.6.
                // For versions below 3.6.6, the Dockerfile will copy ExternalCouchbaseTransactions.sln.bak over the
                // solution file and add a ProjectReference to the external Couchbase.Transactions project.
                if (build instanceof HasVersion && build.implementationVersion().isBelow(ImplementationVersion.from("3.6.6"))){
                    dockerBuildArgs.put('EXTERNAL_TRANSACTIONS', 'yes')
                }

                if (build instanceof BuildMain) {
                    // no extra build args
                }
                else if (build instanceof BuildGerrit) {
                    dockerBuildArgs.put('BUILD_GERRIT', build.gerrit())
                }
                else if (build instanceof HasSha) {
                    dockerBuildArgs.put('SDK_BRANCH', build.sha())
                }
                else if (build instanceof HasVersion) {
                    dockerBuildArgs.put('SDK_BRANCH', 'tags/' + build.version())
                }

                imp.dockerBuild("-f transactions-fit-performer/performers/dotnet/Couchbase.Transactions.FitPerformer/Dockerfile -t $imageName .", dockerBuildArgs)
            }
        }
    }
}
