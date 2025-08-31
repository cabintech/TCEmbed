package com.cabintechglobal.tce;

/**
 * This utility class handles all the details of running an embedded Tomcat
 * server in a Java application. This utility supports execution in an IDE like Eclipse
 * (when resources are in the file system), or execution in a composite JAR
 * file (when deployed as a single-JAR Java application). A composite JAR can be wrapped
 * into a platform executable using the 'launch4j' tool. 
 * 
 * The following is a sample lanuch4j configuration file:
 * 
	<?xml version="1.0" encoding="UTF-8"?>
	<launch4jConfig>
	  <dontWrapJar>false</dontWrapJar>
	  <headerType>gui</headerType>
	  <jar>ct3680-updaterui.jar</jar>
	  <outfile>ct3680-updaterui.exe</outfile>
	  <errTitle></errTitle>
	  <cmdLine></cmdLine>
	  <chdir>.</chdir>
	  <priority>normal</priority>
	  <downloadUrl></downloadUrl>
	  <supportUrl></supportUrl>
	  <stayAlive>false</stayAlive>
	  <restartOnCrash>false</restartOnCrash>
	  <manifest></manifest>
	  <icon></icon>
	  <jre>
	    <path>%JAVA_HOME%;%PATH%</path>
	    <requiresJdk>false</requiresJdk>
	    <requires64Bit>false</requires64Bit>
	    <minVersion>17</minVersion>
	    <maxVersion></maxVersion>
	  </jre>
	</launch4jConfig>
 * 
 * Attempts to create a fully native
 * binary executable using GraalVM were not successful, although it should in theory be
 * possible. (Could not get Tomcat to locate the static web resources or dynamic Servlets).
 * 
 * This is a single-use class, e.g. only one Tomcat embedded server may be
 * created in the JVM. Attempting to start a second server will fail.
 * 
 * The server will dynamically locate and load all the usual resources like
 * servlets, listeners, etc. The assumed directory layout is the structure created
 * by Eclipse for 'Dynamic Web' type projects:
 * 
 * project
 *   src
 *     main
 *       java
 *         <package>
 *           ServletClass1
 *           ServletClass2
 *           Listener1
 *           OtherClasses
 *           ...
 *       webapp
 *         index.html
 *         page2.html
 *         images/
 *         js/
 *         css/
 *         META-INF
 *         WEB-INF
 *         
 * The 'java' directory contains all the server-side JAva code, and 'webapp'
 * contains all the static client content. 
 * 
 * Typical usage (see method comments for details):
 * 
   private static String ROOT_URL = "/myapp";
   public static void main{String[] args) {
   
		// Create and sart the Tomcat web server with our application root URL
  		TomcatEmbedded.startTomcat(ROOT_URL);
  
  		// Install a servlet to stop the server when a GET is performed on ROOT_URL/shutdown
  		TomcatEmbedded.installShutdownServlet("/shutdown");

		// Open a browser window (or tab) on the root URL of the deployed application using the current server port
		TomcatEmbedded.openBrowser("http://localhost:%port%" + ROOT_URL);

		// Wait here indefinitely (until the shutdown servlet is called).
		TomcatEmbedded.waitForShutdown();
		
	}

 * This will start Tomcat and then open a browser window to "myapp/index.html" which will
 * be served to the browser by the (already started) Tomcat server. Interaction between the
 * client and the server then proceeds as usual with the client invoking servlets via HTTP and the
 * server returning responses.
 */

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.WebResourceRoot;
import org.apache.catalina.loader.WebappLoader;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.webresources.DirResourceSet;
import org.apache.catalina.webresources.JarResourceSet;
import org.apache.catalina.webresources.StandardRoot;

import com.vaadin.open.Open;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class TomcatEmbedded {
	
	// Tomcat configuration values //TODO Make these externally configurable
    private static final String DEFAULT_HOST = "localhost";
    private static final String DOC_BASE = ".";
    private static final String ADDITION_WEB_INF_CLASSES = "build/classes";
    private static final String STATIC_ECLIPSE_RESOURCES = "src/main/webapp";
    private static final String WEB_APP_MOUNT = "/";
    private static final String INTERNAL_PATH = "/";
    
	public static Tomcat server = null; // The server instance created by startServer(), null until startServer() is called.
    public static Context context = null; // The Tomcat context for the deployed application, null until startServer() is called.
    
    // Note this is volatile because its value may be changed on one thread, and
    // the updated value needs to be read on another thread without any synchronization.
    private static volatile boolean pendingShutdown = false; 
    
	private TomcatEmbedded() { } // No instances, just static methods
	
	/**
	 * Create a Tomcat server instance, configure it, and start it running. The application URL root may be an empty
	 * string in which case servlets and static content will be deployed at the root (e.g. http://localhost:####/).
	 * If a root is specified it must start with "/" and all resources will be deployed under that
	 * root URL level (e.g. if urlRoot="/myapp" the all resources will appear at http://localhost:####/myapp/...). 
	 * If a root is specified it must not end with "/".
	 * 
	 * The Tomcat server instance is returned and is also stored in the static 'server' field along with
	 * the application context in the static 'context' field.
	 * 
	 * The server will be started on some (dynamically chosen) port number that is currently available. The port
	 * number can be retrieved by calling the .getConnector().getLocalPort() on the returned Tomcat object.
	 * 
	 * The application main thread must not return (or System.exit()) as this will terminate the server.
	 * The main thread can call .getServer().await() on the returned Tomcat object to suspend that thread
	 * and prevent return from the main() method. Another thread may call .stop on the Tomcat object to
	 * gracefully shutdown the server and any thread waiting will resume execution.
	 * 
	 * This method creates a temporary directory in the default system location. The embedded Tomcat server
	 * writes temp files to that directory. This method registers a shutdown hook to delete all the temporary
	 * files and the temp directory when the JVM exits. Note this cleanup can only happen on a 'clean' JVM
	 * exit. If the JVM is halted externally (e.g. the STOP button in Eclipse) then the temporary directory
	 * and files are not cleaned up and may accumulate over time. The name of the temp directory can be
	 * retrieved with the .getServer().getCatalinaBase() method on the Tomcat object. The directory name will
	 * start with "tce-app-"
	 * 
	 * @param urlRoot Root URL for the application, must be empty or start with "/" and not end with "/".
	 * @return Tomcat server instance (also available in the static 'server' field).
	 * @throws Exception Throws if any failure to create or start the server.
	 */
    public static Tomcat startTomcat(String urlRoot) throws Exception {
    	if (server != null) throw new IllegalStateException("Cannot call this method, a server has already been started.");

    	// Create temp directory for TC to use
    	Path tempPath = Files.createTempDirectory("tce-app-");
    	// We drop a small readme.txt here to explain what this directory is for
    	Path readme = Paths.get(tempPath.toString(), "readme.txt");
    	Files.writeString(readme,"This directory is (was) used by a Tomcat embedded web server application.\nIt may be safely removed if the application is no longer running.");
    	
    	// Java does not do this automatically, we have to cleanup on JVM exit
    	deleteOnShutdown(tempPath);

    	// Create the Tomcat server and configure it
    	server = new Tomcat();
    	server.setBaseDir(tempPath.toString());
        server.setHostname(DEFAULT_HOST);
        server.getHost().setAppBase(DOC_BASE);
        server.setPort(0); // Setting the port to 0 will cause Tomcat to locate any unused port number
        server.getConnector(); // Creates a default connector on the port

        // Init the Tomcat 'context' which defines where the server expects to
        // find things like Java servlet classes and static (HTML,CSS,JS) content.
    	
        context = server.addWebapp(urlRoot, DOC_BASE);
        
        // By default servlets are dynamically loaded into the Tomcat container with a classloader
        // that does not (by design) follow usual Java delegation. The result is that classes loaded
        // by the main application are not visible to servlet classes. Thus servlets cannot access
        // static data or easily share class definitions with the main application code. 
        //
        // To allow more seamless sharing of classes and data between the main application and the
        // servlets that run in the embedded Tomcat container, we configure the Tomcat app loader
        // to use conventional class loading delegation.
        WebappLoader loader = new WebappLoader();
        loader.setDelegate(true);
        context.setLoader(loader);
        
        // If running on the file system (e.g. in Eclipse) point Tomcat to the file location
        // of the static resources. If running from a JAR, use a Jar type resource to locate
        // static resources.
        WebResourceRoot resources = new StandardRoot(context);
        String sourceFile = getSourceFile(TomcatEmbedded.class);
        System.err.println("source run file: "+sourceFile);
        if (!sourceFile.endsWith(".jar") && !sourceFile.endsWith(".exe")) {
        	// Tomcat expects (requires) servlets to be located in /WEB-INF/classes, but when
        	// compiling a Java app in Eclipse, all the class files go into /build/classes. So
        	// we create a mapping between the required path for servlets to the physical
        	// location of the class files.
        	String baseDir = new File(ADDITION_WEB_INF_CLASSES).getAbsolutePath();
        	resources.addPreResources(new DirResourceSet(resources, "/WEB-INF/classes", baseDir, INTERNAL_PATH));
        	//System.out.println("Added resources at: "+baseDir);
        	
        	// We need to tell Tomcat where it can find common static resources (HTML, css, etc) and
        	// map that to the web application root.
        	baseDir = new File(STATIC_ECLIPSE_RESOURCES).getAbsolutePath();
        	resources.addPreResources(new DirResourceSet(resources, WEB_APP_MOUNT, baseDir, INTERNAL_PATH));
        	//System.out.println("Added resources at: "+baseDir);
        	
        }
        else {
        	// JAR file static (deployed app)
            resources.addPreResources(new JarResourceSet(resources, "/WEB-INF/classes", sourceFile, INTERNAL_PATH));
            resources.addPreResources(new JarResourceSet(resources, WEB_APP_MOUNT, sourceFile, INTERNAL_PATH));
        }
        
        // Tell Tomcat where the static resources are
        context.setResources(resources);

// OBSOLETE - we now configure Tomcat to properly locate and load annotated servlet classes
//        // It seems hard to get Tomcat to find the servlet classes when running embedded, so
//        // we explicitly add each one to the server from a known list of servlet classes.
//        int servletNum = 0;
//        for (Class<?> servletClass: servletList) {
//        	
//        	// Verify this is a servlet and that it has the WebServlet annotation, from which we can get
//        	// the URL to which this servlet is to be bound.
//        	if (!HttpServlet.class.isAssignableFrom(servletClass)) {
//        		throw new IllegalArgumentException("Class '"+servletClass.getCanonicalName()+"' does not implement the HttpSevlet interface.");
//        	}
//        	if (!servletClass.isAnnotationPresent(WebServlet.class)) {
//        		throw new IllegalArgumentException("Class '"+servletClass.getCanonicalName()+"' does not have the expected WebServlet annotation.");
//        	}
//        	
//        	// The annotation contains a list of URL patterns to which the servlet is to be bound.
//        	WebServlet ws = servletClass.getAnnotation(WebServlet.class);
//        	
//    		// Add an instance of the servlet to the server, the name is arbitrary but must be unique
//    		String servletName = "Servlet"+(servletNum++);
//	        server.addServlet(context.getPath(), servletName, (Servlet)servletClass.getDeclaredConstructor().newInstance());
//	        
//	        // Register all the URL path mappings for this servlet (usually only one). They can be specified as the
//	        // value of the annotation, or as the 'urlpatterns' parameter.
//    		String[] patterns = ws.value().length==0 ? ws.urlPatterns() : ws.value();
//    		
//    		for (String url : patterns) {
//    	        context.addServletMappingDecoded(url, servletName);
//    		}
//        		
//        }      
        
        // Tell the server to init servlets and start listening on the IP port.
        server.start();
        
        return server;

    }	

	/**
	 * This method will block the current thread until (another thread) calls stop() on the 
	 * Tomcat instance. After Tomcat has completed all servlet and other lifecycle events,
	 * the destroy() method will be called to terminate all server threads. The Tomcat object
	 * cannot be used after this method completes.
	 * 
	 * This would typically be called in an application's main() method to prevent the application's
	 * main thread from terminating while the server is still running. When this method returns the
	 * server has completed and the application can terminate safely. 
	 * 
	 * In theory the application should be able to just return from it's main() method, but it is
	 * safer to call System.exit() to insure process termination even if some thread in the server or
	 * the application does not exit gracefully, e.g.
	 * 
	 * public void main(String [] args) {
	 * {
	 *   ...
	 *   TomcatEmbedded.startServer("/myapp");
	 *   ...
	 *   TomcatEmbedded.waitForShutdown();
	 *   ...
	 *   System.exit(0);
	 * }
	 * 
	 * @param tc Tomcat instance on which to wait for termination
	 */
	public static void waitForShutdown() {
    	if (server == null) throw new IllegalStateException("Cannot call this method until the server is started.");

        server.getServer().await();
        try {
        	server.destroy();
        }
        catch (LifecycleException ignore) { }
	}
	
	/**
	 * Opens a web browser window (or tab on an existing window) on the local machine. Exact behavior
	 * with respect to selection of browser, and use of windows and tabs is OS and browser specific. 
	 * The provided URL should be complete with protocol, host name, port number, and path to a resource.
	 * 
	 * If the port number in the URL is specified as "%port%" then the port number the server is listing
	 * on will be substituted.
	 * , e.g.
	 * 
	 * "http://localhost:%port%/myapp/index.html"
	 * 
	 * @param url
	 */
	public static void openBrowser(String url) {
		if (url.contains("%port%")) {
	    	if (server == null) throw new IllegalStateException("Cannot use %port% in URL until the server is started.");
			url = url.replace("%port%", server.getConnector().getLocalPort()+"");
		}
		Open.open(url);
	}
	
    /**
     * Determine the path to the supplied class file.
     * @param mainClass
     * @return
     */
    private static String getSourceFile(Class<?> mainClass) {
        try {
            return new File(mainClass.getProtectionDomain().getCodeSource().
                    getLocation().toURI()).getPath();
        } catch(Exception e) {
            throw new IllegalStateException(e);
        }
    }
    
    /**
     * This method can be called to cancel any pending shutdown. It must be called soon
     * after the shutdown servlet is invoked to cancel the shutdown. If there is no pending
     * shutdown this call has no effect.
     */
    public static void cancelPendingShutdown() {
    	pendingShutdown = false;
    }
    
    /**
     * This will install a servlet into the server that may be invoked by a client
     * using the given servlet URL (e.g. "/shutdown"). This servlet will return a short
     * HTML document to the client indicating that shutdown is being done, and then it
     * will stop the embedded Tomcat server. Any thread waiting in the waitForShutdown()
     * method will resume execution.
     * 
     * An optional "cancel=true" parameter may be supplied by the client as a URL parameter
     * to cancel a shutdown recently requested. If a shutdown is pending, it will be
     * cancelled, otherwise a call with "cancel=true" has no effect. This can be used by
     * a browser application to support a 'reload' scenario where the application calls for
     * shutdown when the DOM document is unloaded, but then is immediately reloaded and
     * the client wants to stop the shutdown because it is now started again.
     * 
     * This method can only be called once. 
     * @param name
     */
    public static void installShutdownServlet(String servletURL) {
    	
    	if (server == null) throw new IllegalStateException("Cannot call this method until the server is started.");
    	
    	// Create a servlet to implement the shutdown process
    	
    	HttpServlet shutdownServlet = new HttpServlet() {
			private static final long serialVersionUID = 1L;
			@Override
    	    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
				
				// If client specified "?cancel=true" on the URL, we just cancel and pending
				// shutdown request. If there is no such request the call has no effect.
				String cancel = req.getParameter("cancel");
				if (cancel != null && cancel.toLowerCase().equals("true")) {
					pendingShutdown = false;
					System.out.println("Server shutdown cancelled.");
					// Return a simple HTML message
	    			resp.getWriter().append(
	    					"<html><head>\n"
	    					+ "</head><body>\n"
	    					+ "Shutdown cancelled.\n"
	    					+ "</body></html>");
					return;
				}
				
				System.out.println("Server shutdown requested.");
				
				// Return a simple HTML message
    			resp.getWriter().append(
    					"<html><head>\n"
    					+ "</head><body>\n"
    					+ "Stopping application.\n"
    					+ "</body></html>");
    			
    			// Run this just a bit later to give time for the response to be sent back to the client
    			// before the server shuts down. The shutdown can be cancelled if the application calls
    			// the cancelServerShutdown() method before this thread completes it's wait time.
    			pendingShutdown = true;
    			new Thread(() -> {
    				try {
    					Thread.sleep(2000);
    					if (pendingShutdown) {
    						server.stop();
    					}
    				}
    				catch (Exception e) {
    					System.err.println("Error attmpting to stop embedded web server.");
    					e.printStackTrace(System.err);
    				}
    			}).start();
    	    }
    	};
    	 
    	// Register this servlet with the server
    	server.addServlet(context.getPath(), "ShutdownServlet", shutdownServlet);      
    	context.addServletMappingDecoded(servletURL, "ShutdownServlet");
    }
    
	/**
	 * Registers a shutdownhook to delete the given (directory or file) when the JVM
	 * exits cleanly. If the exit is not clean this hook may not be called. Borrowed
	 * from
	 * https://stackoverflow.com/questions/15022219/does-files-createtempdirectory-remove-the-directory-after-jvm-exits-normally
	 * 
	 * @param path
	 */
	private static void deleteOnShutdown(final Path path) {
		Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
						@Override
						public FileVisitResult visitFile(Path file,
								@SuppressWarnings("unused") BasicFileAttributes attrs) throws IOException {
							Files.delete(file);
							return FileVisitResult.CONTINUE;
						}

						@Override
						public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
							if (e == null) {
								Files.delete(dir);
								return FileVisitResult.CONTINUE;
							}
							// directory iteration failed
							throw e;
						}
					});
				} catch (IOException e) {
					throw new RuntimeException("Failed to delete " + path, e);
				}
			}
		}));
	}
    
}

//--------------------------------------------------------------------------------------------------
// Example of creating a servlet on-the-fly and adding it to the server.
//--------------------------------------------------------------------------------------------------

//@SuppressWarnings("serial")
//HttpServlet servlet = new HttpServlet() {
//    @Override
//    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
//            throws ServletException, IOException {
//        PrintWriter writer = resp.getWriter();
//         
//        writer.println("<html><title>Welcome</title><body>");
//        writer.println("<h1>Have a Great Day!</h1>");
//        writer.println("</body></html>");
//    }
//};
// 
// 
//tomcatServer.addServlet(tomcatContext.getPath(), "Servlet1", servlet);      
//tomcatContext.addServletMappingDecoded("/go", "Servlet1");
//
//tomcatServer.addServlet(tomcatContext.getPath(), "Servlet2", new DoForMe());
//tomcatContext.addServletMappingDecoded("/DoForMe", "Servlet2");




//--------------------------------------------------------------------------------------------------
// This method to locate servlets in the classpath did not work because the AnnotationScanner
// only works with the URLClassLoader, but embedded Tomcat uses a different classload
// implementation ("AppLoader"?). So instead the utility requires the caller to pass in the list
// of servlet classes.
//--------------------------------------------------------------------------------------------------

//
//long start = System.currentTimeMillis();
//int servletNum = 0;
//for (Class<?> servletClass : AnnotationScanner.findClassesWithAnnotation("com.cabintechglobal.tce", WebServlet.class)) {
//	System.out.println("Processing WebServlet annotation for "+servletClass.getCanonicalName());
//	WebServlet ws = servletClass.getAnnotation(WebServlet.class);
//	if (ws != null) {
//		// Add an instance of the servlet to the server, the name is arbitrary but must be unique
//		String servletName = "Servlet"+(servletNum++);
//        tomcatServer.addServlet(tomcatContext.getPath(), servletName, (Servlet)servletClass.getDeclaredConstructor().newInstance());
//        // Register all the URL path mappings for this servlet (usually only one)
//		String[] patterns = ws.urlPatterns();
//		for (String url : patterns) {
//	        tomcatContext.addServletMappingDecoded(url, servletName);
//		}
//		
//	}
//}
//System.out.println("Scan took "+(System.currentTimeMillis()-start)+"ms"); 
