/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package hudson.plugins.hadoop;

import hudson.FilePath;
import hudson.Plugin;
import hudson.model.Hudson;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.slaves.Channels;
import hudson.util.ClasspathBuilder;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DFSClient;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hadoop plugin.
 *
 * @author Kohsuke Kawaguchi
 */
public class PluginImpl extends Plugin {
    /*package*/ Channel channel;
    /*package*/ HadoopPage page = new HadoopPage();
    private String masterHostName;

    @Override
    public void start() throws Exception {
        Hudson.getInstance().getActions().add(page);
    }

    /**
     * Determines the HDFS URL.
     */
    public String getHdfsUrl() throws MalformedURLException {
        InetSocketAddress a = getHdfsAddress();
        if(a==null)     return null;
        return "hdfs://"+a.getHostName()+":"+a.getPort()+"/";
    }

    /**
     * Determines the HDFS connection endpoint.
     */
    public InetSocketAddress getHdfsAddress() throws MalformedURLException {
        // TODO: port should be configurable
        if(masterHostName==null)
            return null;
        return new InetSocketAddress(masterHostName,9000);
    }

    /**
     * Connects to this HDFS.
     */
    public DFSClient createDFSClient() throws IOException {
        return new DFSClient(getHdfsAddress(),new Configuration(false));
    }

    /**
     * Determines the job tracker address.
     */
    public String getJobTrackerAddress() throws MalformedURLException {
        // TODO: port should be configurable
        if(masterHostName==null)
            return null;
        return masterHostName+":"+JOB_TRACKER_PORT_NUMBER;
    }

    /**
     * Launches Hadoop in a separate JVM.
     *
     * @param rootDir
     *      The slave/master root.
     */
    static /*package*/ Channel createHadoopVM(File rootDir, TaskListener listener) throws IOException, InterruptedException {
        // install Hadoop if it's not there
        rootDir = new File(rootDir,"hadoop");
        FilePath distDir = new FilePath(new File(rootDir,"dist"));
        distDir.installIfNecessaryFrom(PluginImpl.class.getResource("hadoop.tar.gz"),listener,"Hadoop");

        File logDir = new File(rootDir,"logs");
        logDir.mkdirs();

        return Channels.newJVM("Hadoop",listener,null,
                new ClasspathBuilder().addAll(distDir,"hadoop-*-core.jar").addAll(distDir,"lib/**/*.jar").add(distDir.child("conf")),
                Collections.singletonMap("hadoop.log.dir",logDir.getAbsolutePath()));
    }

    /**
     * Compute the host name that Hadoop nodes can be used to talk to Name node.
     *
     * <p>
     * We prefer to use {@link Hudson#getRootUrl()}, except we have to watch out for a possibility
     * that it points to a front end (like apache, router with port-forwarding, etc.), and if that is the case,
     * use some heuristics to find out a usable host name.
     *
     * TODO: move this to {@code Hudson.toComputer().getHostName()}. 
     */
    String getMasterHostName() throws IOException, InterruptedException {
        // check if rootURL is reliable
        Hudson h = Hudson.getInstance();
        String rootUrl = h.getRootUrl();
        if (rootUrl==null) {
            // the only option is to auto-detect.
            String real = h.toComputer().getHostName();
            LOGGER.fine("Hudson root URL isn't configured. Using "+real+" instead");
            return real;
        }

        // according to Hudson's setting, this is the host name that we can use to connect to master,
        // at least for HTTP. See if we can connect to the arbitrary port in this way.
        final String hostName = new URL(rootUrl).getHost();
        final ServerSocket ss = new ServerSocket(0);

        Thread t = new Thread() {
            @Override
            public void run() {
                try {
                    ss.accept();
                } catch (IOException e) {
                    // shouldn't happen
                    LOGGER.log(Level.INFO, "Failed to accept", e);
                } finally {
                    try {
                        ss.close();
                    } catch (IOException e) {
                        // ignore
                    }
                }
            }
        };
        t.start();

        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(hostName, ss.getLocalPort()),1000);
            s.close();

            // yep, it worked
            return hostName;
        } catch (IOException e) {
            // no it didn't
            String real = h.toComputer().getHostName();
            LOGGER.fine("Hudson root URL "+rootUrl+" looks like a front end. Using "+real+" instead");
            return real;
        }
    }

    public void postInit() throws IOException, InterruptedException {
        masterHostName = getMasterHostName();
    }

    @Override
    public void stop() throws Exception {
        if(channel!=null)
            channel.close();
    }

    public static PluginImpl get() {
        return Hudson.getInstance().getPlugin(PluginImpl.class);
    }

    /**
     * Job tracker port number.
     */
    public static final int JOB_TRACKER_PORT_NUMBER = 50040;

    private static final Logger LOGGER = Logger.getLogger(PluginImpl.class.getName());
}
