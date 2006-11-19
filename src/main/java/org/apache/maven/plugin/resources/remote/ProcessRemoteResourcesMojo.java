package org.apache.maven.plugin.resources.remote;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.resources.remote.io.xpp3.RemoteResourcesBundleXpp3Reader;
import org.apache.maven.shared.downloader.Downloader;
import org.apache.maven.shared.downloader.DownloadException;
import org.apache.maven.shared.downloader.DownloadNotFoundException;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.project.MavenProject;
import org.apache.velocity.VelocityContext;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.codehaus.plexus.velocity.VelocityComponent;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.io.FileWriter;
import java.util.List;
import java.util.Iterator;
import java.util.Enumeration;
import java.util.ArrayList;
import java.util.Date;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.text.DateFormat;
import java.text.SimpleDateFormat;

/**
 * Pull down artifacts containing remote resources and process the resources contained
 * inside the artifact.
 *
 * @goal process
 * @phase generate-resources
 */
public class ProcessRemoteResourcesMojo
    extends AbstractMojo
{
    /**
     * @parameter expression="${localRepository}
     */
    private ArtifactRepository localRepository;

    /**
     * @parameter expression="${project.remoteRepositories}
     */
    private ArrayList remoteRepositories;

    /**
     * @parameter expression="${project}"
     */
    private MavenProject project;

    /**
     * @parameter expression="${project.build.outputDirectory}"
     */
    private File outputDirectory;

    /**
     * @parameter
     */
    private ArrayList artifacts;

    /**
     * @component
     */
    private Downloader downloader;

    /**
     * @component
     */
    private VelocityComponent velocity;

    /**
     * @parameter expression="${workDirectory}" default-value="${project.build.directory}/remote-resources"
     */
    private File workDirectory;

    public void execute()
        throws MojoExecutionException
    {
        if ( StringUtils.isEmpty( project.getInceptionYear() ) )
        {
            throw new MojoExecutionException( "You must specify an inceptionYear." );
        }

        RemoteResourcesClassLoader classLoader = new RemoteResourcesClassLoader();

        for ( Iterator i = artifacts.iterator(); i.hasNext(); )
        {
            String artifactDescriptor = (String) i.next();

            // groupId:artifactId:version
            String[] s = StringUtils.split( artifactDescriptor, ":" );

            try
            {
                File artifact = downloader.download( s[0], s[1], s[2], localRepository, remoteRepositories );

                classLoader.addURL( artifact.toURI().toURL() );
            }
            catch ( DownloadException e )
            {
                throw new MojoExecutionException( "Error downloading resources JAR.", e );
            }
            catch ( DownloadNotFoundException e )
            {
                throw new MojoExecutionException( "Resources JAR cannot be found.", e );
            }
            catch ( MalformedURLException e )
            {
                // Won't happen.
            }
        }

        ClassLoader old = Thread.currentThread().getContextClassLoader();

        Thread.currentThread().setContextClassLoader( classLoader );

        InputStreamReader reader = null;

        VelocityContext context = new VelocityContext();

        context.put( "project", project );

        String year = new SimpleDateFormat("yyyy").format( new Date() );

        context.put( "presentYear", year );

        if ( project.getInceptionYear().equals( year ) )
        {
            context.put( "projectTimespan", year );
        }
        else
        {
            context.put( "projectTimespan", project.getInceptionYear() + "-" + year );
        }

        try
        {
            Enumeration e = classLoader.getResources( BundleRemoteResourcesMojo.RESOURCES_MANIFEST );

            URL url = (URL) e.nextElement();

            URLConnection conn = url.openConnection();

            conn.connect();

            reader = new InputStreamReader( conn.getInputStream() );

            RemoteResourcesBundleXpp3Reader bundleReader = new RemoteResourcesBundleXpp3Reader();

            RemoteResourcesBundle bundle = bundleReader.read( reader );

            for ( Iterator i = bundle.getRemoteResources().iterator(); i.hasNext(); )
            {
                String resource = (String) i.next();

                File f = new File( outputDirectory, resource );

                FileUtils.mkdir( f.getParentFile().getAbsolutePath() );

                Writer writer = new FileWriter( f );

                velocity.getEngine().mergeTemplate( resource, context, writer );

                writer.close();
            }
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Error finding remote resources manifests", e );
        }
        catch ( XmlPullParserException e )
        {
            throw new MojoExecutionException( "Error parsing remote resource bundle descriptor.", e );
        }
        catch ( Exception e )
        {
            throw new MojoExecutionException( "Error rendering velocity resource.", e );
        }

        finally
        {
            IOUtil.close( reader );
        }

        Thread.currentThread().setContextClassLoader( old );
    }
}
