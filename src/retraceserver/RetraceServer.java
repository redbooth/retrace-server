/*
 * Copyright (c) Air Computing Inc., 2013.
 * Author: Gregory Schlomoff <greg@aerofs.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package retraceserver;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.*;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.codec.frame.DelimiterBasedFrameDecoder;
import org.jboss.netty.handler.codec.frame.Delimiters;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.Executors;

public class RetraceServer
{
    private final int port;
    private final String host;
    private final boolean verbose;
    private final Map<String, Retracer> retracers = Collections.synchronizedMap(new RetracersCache(100));

    public static void main(String[] args)
    {
        boolean verbose = false;
        int port = 50123;

        try {
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("-v")) {
                    verbose = true;
                } else if (args[i].equals("-p")) {
                    port = Integer.parseInt(args[i+1]);
                    i++;
                } else {
                    System.out.println("Invalid argument: " + args[i]);
                    throw new IllegalArgumentException();
                }
            }
        } catch (Exception e) {
            System.out.println("Valid arguments are:");
            System.out.println("  -v           verbose mode. Prints debugging information to stdout");
            System.out.println("  -p <number>  port number.");
            System.exit(1);
        }

        new RetraceServer(verbose, "127.0.0.1", port).start();
    }

    private RetraceServer(boolean verbose, String host, int port)
    {
        this.verbose = verbose;
        this.host = host;
        this.port = port;
    }

    private void start()
    {
        // Configure the server.
        ServerBootstrap bootstrap = new ServerBootstrap(
                new NioServerSocketChannelFactory(Executors.newCachedThreadPool(),
                        Executors.newCachedThreadPool()));

        // Set up the pipeline factory to accept line delimited input.
        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            @Override
            public ChannelPipeline getPipeline() throws Exception
            {
                return Channels.pipeline(
                        new DelimiterBasedFrameDecoder(1024, Delimiters.lineDelimiter()),
                        new RetraceServerHandler());
            }
        });

        // Bind and start to accept incoming connections.
        InetSocketAddress address = new InetSocketAddress(host, port);
        bootstrap.bind(address);
        System.out.println("RetraceSrv listening on " + host + ":" + port);
    }

    /**
     * This is the class that receives the data from the client and writes back the output
     */
    private class RetraceServerHandler extends SimpleChannelUpstreamHandler
    {
        @Override
        public void messageReceived(ChannelHandlerContext ctx, MessageEvent e)
        {
            String request = new String(((ChannelBuffer) e.getMessage()).array());
            String response = "ERROR: Unknown error";

            try {
                response = react(request);
            } catch (Throwable ex) {
                response = "ERROR: " + ex.getMessage();
                System.err.println(response);
            }

            if (verbose) {
                System.out.println("C: " + request);
                System.out.println("S: " + response);
            }

            response += "\n";
            ChannelBuffer buffer = ChannelBuffers.copiedBuffer(response.getBytes());
            e.getChannel().write(buffer);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e)
        {
            System.err.println("Unrecoverable error: " + e.getCause());
            e.getChannel().close();
        }

        /**
         * Processes a request. A request has the following form:
         *
         * VERSION CLASSNAME [METHODNAME [LINENUMBER]]
         *
         * Example:
         * C:    0.4.116 com.aerofs.A b 100
         * S:    OK: com.aerofs.UnobfuscatedClass MethodName
         *
         * (Note that line numbers are not returned in the response)
         *
         * If there are no unobfuscated matches, the obfuscated names are returned instead.
         * If the line number isn't supplied, several method names may match. In this case, they will all be returned,
         * separated by commas.
         *
         * Example:
         * C:   0.4.116 com.aerofs.A b
         * S:   OK: com.aerofs.UnobfuscatedClass MethodName1,MethodName2
         *
         * If there is an error while processing the request, the response will be "ERROR: " followed by an error
         * message.
         */
        private String react(String request) throws Exception
        {
            // Read the first argument of the request (the version number)
            String[] args = request.split(" ");
            if (args.length < 2) throw new Exception("couldn't parse the request");
            String version = args[0];

            // Get the Retracer for that version.
            Retracer retracer = retracers.get(version);
            if (retracer == null) {
                File mapping = getMappingFile(version);
                if (!mapping.exists()) throw new Exception("file not found: " + mapping);
                retracer = new Retracer(mapping);
                retracers.put(version, retracer);
            }

            // Get the other arguments of the request and un-obfuscate them
            String className = args[1];
            String methodName = (args.length >= 3) ? args[2] : null;
            int lineNumber = (args.length >= 4) ? Integer.parseInt(args[3]) : -1;

            Retracer.Result retraced = retracer.retrace(className, methodName, lineNumber);

            return "OK: " + retraced.className + " " + join(retraced.methodNames, ",");
        }

        private File getMappingFile(String version)
        {
            return new File("/maps/aerofs-" + version + "-public.map");
        }

        private String join(List list, String sep)
        {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(sep);
                sb.append(list.get(i).toString());
            }
            return sb.toString();
        }
    }

    private static class RetracersCache extends LinkedHashMap<String, Retracer>
    {
        private final int cacheSize;

        RetracersCache(int cacheSize)
        {
            this.cacheSize = cacheSize;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Retracer> entry)
        {
            return size() > cacheSize;
        }
    }
}
