This project is a utility to simplify embedding a Tomcat server into a Java application. It encapsulates
all the details of configuration and execution. Features:
- A few simple static methods to create and run the embedded server
- Default configuration supports execution in Eclipse (with resources on the file system) or as a composit jar file (with resources in the jar).
- Composit jar can also be wrapped into a platform executable using the 'lanuch4j' tool.
- An optional 'shutdown' servlet that allows the client to shutdown the server.
- The embedded server supports all Tomcat features including dynamic loading of servlets (synchronous or async), listeners, etc. using the usual annotations (e.g. @WebServlet).

If the layout of classes and static web resources is not that of the usual Eclipse 'Dynamic Web' project, some adjustment of the static path values may be needed (see issue https://github.com/cabintech/TCEmbed/issues/1#issue-3370439441).

This project can be used to create desktop Java applications with a web browser user interface. 
