/*
 * ========================================================================
 *
 * Codehaus Cargo, copyright 2004-2011 Vincent Massol, 2012-2023 Ali Tokmen.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * ========================================================================
 */
package org.codehaus.cargo.maven3;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.List;

import org.apache.maven.archiver.MavenArchiveConfiguration;
import org.apache.maven.archiver.MavenArchiver;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.transfer.artifact.install.ArtifactInstaller;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.transfer.dependencies.resolve.DependencyResolver;
import org.codehaus.cargo.maven3.io.xpp3.UberWarXpp3Reader;
import org.codehaus.cargo.maven3.merge.MergeWebXml;
import org.codehaus.cargo.maven3.merge.MergeXslt;
import org.codehaus.cargo.module.merge.DocumentStreamAdapter;
import org.codehaus.cargo.module.merge.MergeException;
import org.codehaus.cargo.module.merge.MergeProcessor;
import org.codehaus.cargo.module.webapp.DefaultWarArchive;
import org.codehaus.cargo.module.webapp.merge.MergedWarArchive;
import org.codehaus.cargo.module.webapp.merge.WarArchiveMerger;
import org.codehaus.plexus.archiver.war.WarArchiver;
import org.codehaus.plexus.context.Context;
import org.codehaus.plexus.context.ContextException;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Contextualizable;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

/**
 * Builds an uber war.
 */
@Mojo(
    name = "uberwar", defaultPhase = LifecyclePhase.PACKAGE,
    requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true)
public class UberWarMojo extends AbstractUberWarMojo implements Contextualizable
{
    /**
     * The directory for the generated WAR.
     */
    @Parameter(property = "project.build.directory", required = true)
    private String outputDirectory;

    /**
     * The name of the generated WAR.
     */
    @Parameter(property = "project.build.finalName", required = true)
    private String warName;

    /**
     * The file name to use for the merge descriptor.
     */
    @Parameter
    private File descriptor;

    /**
     * Attempt to resolve dependencies, rather than simply merging the files in WEB-INF/lib. This is
     * an experimental feature.
     */
    @Parameter
    private boolean resolveDependencies = false;

    /**
     * The id to use for the merge descriptor.
     */
    @Parameter
    private String descriptorId;

    /**
     * Maven artifact resolver, used to dynamically resolve JARs for the containers and also to
     * resolve the JARs for the embedded container's classpaths.
     */
    @Component
    private ArtifactResolver artifactResolver;

    /**
     * Maven dependency resolver, used to dynamically resolve dependencies of artifacts.
     */
    @Component
    private DependencyResolver dependencyResolver;

    /**
     * Maven project building request.
     */
    @Parameter(
        defaultValue = "${session.projectBuildingRequest}", readonly = true, required = true)
    private ProjectBuildingRequest projectBuildingRequest;

    /**
     * The Maven project.
     */
    @Parameter(property = "project", readonly = true, required = true)
    private MavenProject mavenProject;

    /**
     * The Maven session.
     */
    @Parameter(property = "session", readonly = true, required = true)
    private MavenSession mavenSession;

    /**
     * The archive configuration to use. See <a
     * href="http://maven.apache.org/shared/maven-archiver/index.html">Maven Archiver Reference</a>.
     */
    @Parameter
    private MavenArchiveConfiguration archive = new MavenArchiveConfiguration();

    /**
     * Maven project builder, used to calculate dependencies.
     */
    @Component
    private ProjectBuilder mavenProjectBuilder;

    /**
     * Maven artifact installer, used to calculate dependencies.
     */
    @Component
    private ArtifactInstaller installer;

    /**
     * @return Parent file of the descriptor.
     */
    protected File getConfigDirectory()
    {
        return descriptor.getParentFile();
    }

    /**
     * Executes the UberWarMojo on the current project.
     * 
     * @throws MojoExecutionException if an error occured while building the webapp
     */
    @Override
    public void execute() throws MojoExecutionException
    {
        Reader r = null;

        if (this.descriptor != null)
        {
            try
            {
                r = new FileReader(this.descriptor);
            }
            catch (FileNotFoundException ex)
            {
                throw new MojoExecutionException("Could not find specified descriptor");
            }
        }
        else if (this.descriptorId != null)
        {
            InputStream resourceAsStream = getClass().getResourceAsStream(
                "/uberwar/" + this.descriptorId + ".xml");
            if (resourceAsStream == null)
            {
                throw new MojoExecutionException("Descriptor with ID '" + this.descriptorId
                    + "' not found");
            }
            r = new InputStreamReader(resourceAsStream);
        }
        else
        {
            throw new MojoExecutionException("You must specify descriptor or descriptorId");
        }

        try
        {
            UberWarXpp3Reader reader = new UberWarXpp3Reader();
            MergeRoot root = reader.read(r);

            // Add the war files
            WarArchiveMerger wam = new WarArchiveMerger();
            List<String> wars = root.getWars();
            if (wars.isEmpty())
            {
                addAllWars(wam);
            }
            else
            {
                for (String id : wars)
                {
                    addWar(wam, id);
                }
            }

            if (resolveDependencies)
            {
                wam.setMergeJarFiles(false);
                addAllTransitiveJars(wam);
            }
            else
            {
                // Just look at our dependent JAR files instead
                addAllDependentJars(wam);
            }

            // List of <merge> nodes to perform, in order
            for (Object mergeObject : root.getMerges())
            {
                Merge merge = (Merge) mergeObject;
                doMerge(wam, merge);
            }

            File assembleDir = new File(this.outputDirectory, this.warName);
            File warFile = new File(this.outputDirectory, this.warName + ".war");

            // Merge to directory
            MergedWarArchive output = (MergedWarArchive) wam.performMerge();
            output.merge(assembleDir.getAbsolutePath());

            // Archive to WAR file
            WarArchiver warArchiver = new WarArchiver();
            warArchiver.addDirectory(assembleDir);
            warArchiver.setIgnoreWebxml(false);

            MavenArchiver mar = new MavenArchiver();
            mar.setArchiver(warArchiver);
            mar.setOutputFile(warFile);
            mar.createArchive(mavenSession, mavenProject, archive);

            getProject().getArtifact().setFile(warFile);
        }
        catch (XmlPullParserException e)
        {
            throw new MojoExecutionException("Invalid XML descriptor", e);
        }
        catch (Exception e)
        {
            throw new MojoExecutionException("Exception creating Uberwar", e);
        }
    }

    /**
     * Do thr actual merge.
     * @param wam WAR archive merger.
     * @param merge Merge to apply.
     * @throws MojoExecutionException If anything goes wrong.
     */
    private void doMerge(WarArchiveMerger wam, Merge merge) throws MojoExecutionException
    {
        try
        {
            String type = merge.getType();
            String file = merge.getFile();
            String document = merge.getDocument();
            String clazz = merge.getClassname();

            MergeProcessor merger = null;

            if (type != null)
            {
                if (type.equalsIgnoreCase("web.xml"))
                {
                    merger = new MergeWebXml(getConfigDirectory()).create(wam, merge);
                }
                else if (type.equalsIgnoreCase("xslt"))
                {
                    merger = new MergeXslt(descriptor.getParentFile()).create(wam, merge);
                }
            }
            else
            {
                merger = (MergeProcessor)
                    Class.forName(clazz).getDeclaredConstructor().newInstance();
            }

            if (merger != null)
            {
                if (document != null)
                {
                    merger = new DocumentStreamAdapter(merger);
                    wam.addMergeProcessor(document, merger);
                }
                else if (file != null)
                {
                    wam.addMergeProcessor(file, merger);
                }

                // merger.performMerge();
            }
        }
        catch (Exception e)
        {
            throw new MojoExecutionException("Problem in file merge", e);
        }
    }

    /**
     * Add all JAR files into the WAR file, calculated transitively and resolved in the normal
     * 'maven' way (I.E if 2 war files contain different versions, resolve to using only *1*
     * version).
     * @param wam WAR archive manager.
     * @throws MojoExecutionException If anything goes wrong.
     */
    protected void addAllTransitiveJars(WarArchiveMerger wam) throws MojoExecutionException
    {
        DependencyCalculator dc =
            new DependencyCalculator(dependencyResolver, artifactResolver, projectBuildingRequest,
                mavenProject, mavenProjectBuilder, installer);

        try
        {
            for (File f : dc.execute())
            {
                wam.addMergeItem(f);
            }
        }
        catch (Exception e)
        {
            throw new MojoExecutionException("Problem merging dependent JAR files", e);
        }
    }

    /**
     * Add all the JAR files specified into the merge - these will appear in the WEB-INF/lib
     * directory.
     * @param wam WAR archive manager.
     * @throws MojoExecutionException If anything goes wrong.
     */
    protected void addAllDependentJars(WarArchiveMerger wam) throws MojoExecutionException
    {
        for (Object artifactObject : getProject().getArtifacts())
        {
            Artifact artifact = (Artifact) artifactObject;

            ScopeArtifactFilter filter = new ScopeArtifactFilter(Artifact.SCOPE_RUNTIME);
            if (!artifact.isOptional() && filter.include(artifact))
            {
                String type = artifact.getType();

                if ("jar".equals(type))
                {
                    try
                    {
                        wam.addMergeItem(artifact.getFile());
                    }
                    catch (MergeException e)
                    {
                        throw new MojoExecutionException("Problem merging WAR", e);
                    }
                }
            }
        }
    }

    /**
     * Add a WAR to the uberwar.
     * @param wam WAR archive merger.
     * @param artifactIdent Artifact identifier.
     * @throws MojoExecutionException If mojos fail.
     * @throws IOException If reading or writing fails.
     */
    protected void addWar(WarArchiveMerger wam, String artifactIdent)
        throws MojoExecutionException, IOException
    {
        for (Object artifactObject : getProject().getArtifacts())
        {
            Artifact artifact = (Artifact) artifactObject;

            ScopeArtifactFilter filter = new ScopeArtifactFilter(Artifact.SCOPE_RUNTIME);
            if (!artifact.isOptional() && filter.include(artifact))
            {
                String type = artifact.getType();
                if ("war".equals(type))
                {
                    String name = artifact.getGroupId() + ":" + artifact.getArtifactId();
                    if (name.equals(artifactIdent))
                    {
                        try
                        {
                            wam.addMergeItem(new DefaultWarArchive(artifact.getFile().getPath()));
                        }
                        catch (MergeException e)
                        {
                            throw new MojoExecutionException("Problem merging WAR", e);
                        }
                        return;
                    }
                }
            }
        }

        // If we get here, we specified something for which there was no dependency
        // matching
        throw new MojoExecutionException("Could not find a dependent WAR file matching "
            + artifactIdent);
    }

    /**
     * Add all WARs from a merger.
     * @param wam WAR archive merger.
     * @throws MojoExecutionException If mojos fail.
     * @throws IOException If reading or writing fails.
     */
    protected void addAllWars(WarArchiveMerger wam) throws MojoExecutionException, IOException
    {
        for (Object artifactObject : getProject().getArtifacts())
        {
            Artifact artifact = (Artifact) artifactObject;

            ScopeArtifactFilter filter = new ScopeArtifactFilter(Artifact.SCOPE_RUNTIME);

            if (!artifact.isOptional() && filter.include(artifact))
            {
                String type = artifact.getType();
                if ("war".equals(type))
                {
                    try
                    {
                        wam.addMergeItem(new DefaultWarArchive(artifact.getFile().getPath()));
                    }
                    catch (MergeException e)
                    {
                        throw new MojoExecutionException("Problem merging WAR", e);
                    }
                }
            }
        }
    }

    /**
     * Does nothing.
     * {@inheritDoc}
     */
    @Override
    public void contextualize(Context context) throws ContextException
    {
        // Nothing to do
    }

}
