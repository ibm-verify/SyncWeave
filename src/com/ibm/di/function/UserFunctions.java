/*
 * Copyright contributors to the SyncWeave project
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.function;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.activation.DataHandler;
import javax.activation.FileDataSource;
import javax.mail.Address;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.URIResolver;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.apache.xpath.XPathAPI;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.traversal.NodeIterator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.ibm.di.automation.COMProxy;
import com.ibm.di.automation.IDispatch;
import com.ibm.di.config.interfaces.AssemblyLineConfig;
import com.ibm.di.config.interfaces.ExternalPropertiesConfig;
import com.ibm.di.config.interfaces.MetamergeConfig;
import com.ibm.di.config.interfaces.ScriptConfig;
import com.ibm.di.config.interfaces.TDIProperties;
import com.ibm.di.connector.ConnectorInterface;
import com.ibm.di.entry.Attribute;
import com.ibm.di.entry.Entry;
import com.ibm.di.exceptions.AbortALException;
import com.ibm.di.exceptions.ContinueLoopException;
import com.ibm.di.exceptions.ExitBranchException;
import com.ibm.di.exceptions.IgnoreEntryException;
import com.ibm.di.exceptions.RestartEntryException;
import com.ibm.di.exceptions.RetryEntryException;
import com.ibm.di.exceptions.SkipEntryException;
import com.ibm.di.exceptions.SkipToException;
import com.ibm.di.fc.FunctionInterface;
import com.ibm.di.loader.IDILoader;
import com.ibm.di.parser.LDIFParser;
import com.ibm.di.parser.ParserInterface;
import com.ibm.di.plugin.security.pki.IDIPasswordCrypto;
import com.ibm.di.queue.MemBufferQ;
import com.ibm.di.queue.MemBufferQFactory;
import com.ibm.di.script.ScriptEngineOptions;
import com.ibm.di.server.AssemblyLine;
import com.ibm.di.server.AssemblyLinePool;
import com.ibm.di.server.Log;
import com.ibm.di.server.Monitor;
import com.ibm.di.server.RS;
import com.ibm.di.server.RSInterface;
import com.ibm.di.server.ResourceHash;
import com.ibm.di.server.SearchCriteria;
import com.ibm.di.server.Sequence;
import com.ibm.di.server.TaskCallBlock;
import com.ibm.di.store.StoreFactory;
import com.ibm.di.util.FileUtils;
import com.ibm.di.util.ParameterSubstitution;
import com.ibm.icu.text.SimpleDateFormat;
import com.ibm.icu.util.StringTokenizer;

/**
 * This class contains a number of convenience methods widely used by the
 * scripting environment. An instance of this class is available in scripts,
 * with the scripting name of <i>system</i>.
 */

public class UserFunctions {
	/**
	 * Copyright.
	 */
	@SuppressWarnings("unused")
	private static final String COPYRIGHT = com.ibm.di.server.CopyRight.OBJECT_CODE;

	/**
	 * Name of the properties file.
	 */
	private static final String PROPERTIES_FILE = "miserver";

	/**
	 * The Exception object set by the last call in this library.
	 */
	public Exception lastError;

	/**
	 * {@link RSInterface} object.
	 */
	public RSInterface server = null;

	/** Array of invalid XML characters that need to be filtered. */
	public final static char[] INVALID_XML_CHARS = { '\u0000', '\u0001', '\u0002', '\u0003', '\u0004', '\u0005', '\u0006',
			'\u0007', '\u0008', '\u000b', '\u000c', '\u000e', '\u000f', '\u0010', '\u0011', '\u0012', '\u0013', '\u0014', '\u0015',
			'\u0016', '\u0017', '\u0018', '\u0019', '\u001f' };

	/**
	 * NLS Property set holding name-value pairs for the resource.
	 */
	private static ResourceHash sResHash = ResourceHash.getHash(PROPERTIES_FILE);

	/**
	 * Default constructor.
	 */
	public UserFunctions() {
		server = SystemFunctions.getServer();
	}

	/**
	 * Constructor with one parameter.
	 * 
	 * @param server
	 *            server instance
	 */
	public UserFunctions(RSInterface server) {
		this.server = server;
	}

	/**
	 * Returns the RS instance associated with the current ThreadGroup, or the
	 * dummy RSInterface object defined by the Config Editor. Although this
	 * method is public, it is meant for internal use,. The usual way to get the
	 * current RS instance would be to use the <code>main</code> object in
	 * JavaScript.
	 * 
	 * @return the {@link RS} instance or <code>null</code> if it couldn't be
	 *         found, e.g. because the current Thread was not created by the TDI
	 *         framework.
	 */
	public RSInterface getServer() {
		return (server == null ? SystemFunctions.getServer() : server);
	}

	/**
	 * Remove characters from a string. For example, if you want to remove all
	 * blanks from the string "J O P" then you would use <code>
	 * remove (" ", "J O P")</code>
	 * . The returned value would then be "JOP".
	 * 
	 * <pre>
	 * var a = &quot;A string with blanks and vowels&quot;;
	 * var b = system.remove(&quot;AEIOUaeiou &quot;, a);
	 * task.logmsg(&quot;Result: &quot; + b); // &quot;strngwthblnksndvwls&quot;
	 * </pre>
	 * 
	 * @param s
	 *            The characters to be removed
	 * @param source
	 *            The string from which characters are removed
	 * @return string with removed characters specified by <code>s</code>
	 * @throws Exception if the operation fails
	 */
	public String remove(String s, String source) throws Exception {
		if (source == null)
			return null;
		if (s == null)
			return source;

		StringBuffer ns = new StringBuffer();
		char ch;

		for (int i = 0; i < source.length(); i++) {
			ch = source.charAt(i);
			if (s.indexOf(ch) == -1)
				ns.append(ch);
		}

		return ns.toString();
	}

	/**
	 * Trims leading/trailing white-space from a string. Returns an empty string
	 * if the argument is null.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var a = &quot;   A string with leading/trailing white-spaces   &quot;;
	 * var b = system.trim(a);
	 * task.logmsg(&quot;Result: &quot; + b); // &quot;A string with leading/trailing white-spaces&quot;
	 * </pre>
	 * 
	 * @param str
	 *            The string to trim
	 * @return The trimmed string
	 */
	public String trim(String str) {
		return str == null ? "" : str.trim();
	}

	/**
	 * Convert a string to a java.lang.Integer object.
	 * 
	 * @param str
	 *            The string with a number
	 * @return The Integer object
	 * @throws Exception if the string cannot be parsed as an integer
	 */
	public Integer toInt(String str) throws Exception {
		return Integer.valueOf(str);
	}

	/**
	 * Returns true if a string holds a valid Integer.
	 * 
	 * @param str
	 *            The string to test
	 * @return True if the string can be converted to an Integer
	 */
	public boolean isValidInt(String str) {
		try {
			toInt(str);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Opens a file in append mode and returns the associated BufferedWriter
	 * object. The default character encoding is used.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var out = system.openFileForAppend(&quot;out.txt&quot;);
	 * out.write(&quot;Hello world!&quot;);
	 * out.newLine();
	 * out.close();
	 * </pre>
	 * 
	 * @param path
	 *            The file path to open. If the file does not exist it is
	 *            created.
	 * @return The BufferedWriter object
	 * @throws Exception
	 */
	public BufferedWriter openFileForAppend(String path) throws Exception {
		FileWriter w = new FileWriter(path, true);
		return new BufferedWriter(w);
	}

	/**
	 * Opens a file in output mode and returns the associated BufferedWriter
	 * object.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var out = system.openFileForOutput(&quot;out.txt&quot;);
	 * out.write(&quot;Hello world!&quot;);
	 * out.newLine();
	 * out.close();
	 * </pre>
	 * 
	 * @param path
	 *            The file path to open (overwrites existing file)
	 * @return The BufferedWriter object
	 * @throws Exception if the file cannot be opened or created
	 */
	public BufferedWriter openFileForOutput(String path) throws Exception {
		FileWriter w = new FileWriter(new File(path));
		return new BufferedWriter(w);
	}

	/**
	 * Opens a file for input and returns the associated BufferedReader object.
	 * The default character encoding is used.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var inp = system.openFileForInput(&quot;inp.txt&quot;);
	 * var str = inp.readLine();
	 * if (str == null)
	 * 	task.logmsg(&quot;End of file&quot;);
	 * inp.close();
	 * </pre>
	 * 
	 * @param path
	 *            The file path to open
	 * @return The BufferedReader object
	 * @throws Exception
	 *             FileNotFoundException
	 */
	public BufferedReader openFileForInput(String path) throws Exception {
		BufferedReader r = new BufferedReader(new FileReader(new File(path)));
		return r;
	}

	/**
	 * Writes a string plus a CRLF using a Writer object.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var a = &quot;Some line with text.&quot;;
	 * var b = &quot;Another line with text.&quot;;
	 * var fileWriter = new java.io.FileWriter(&quot;c:\\docs\\myfile.txt&quot;);
	 * system.writeln(fileWriter, a);
	 * system.writeln(fileWriter, b);
	 * </pre>
	 * 
	 * The result in the file would look like this:
	 * <p>
	 * <i> Some line with text. <br>
	 * Another line with text. </i>
	 * 
	 * @param w
	 *            The writer object
	 * @param str
	 *            The string to write
	 * @throws Exception
	 */
	public void writeln(Writer w, String str) throws Exception {
		w.write(str + "\r\n");
		w.flush();
	}

	/**
	 * Sends an email message. Make sure the <i>mail.smtp.host</i> Java property
	 * is configured with the hostname of a valid SMTP server.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 *      var res = system.sendMail(&quot;Sender&quot;,&quot;address1@mail.or,
	 *      		address2@mail.or&quot;,&quot;Subject&quot;,&quot;Message text&quot;,
	 *      		&quot;C:\\docs\\myfile.txt&quot;);
	 *      if(res != null)
	 *      	task.logmsg(&quot;Error occurred: &quot;+res);
	 * </pre>
	 * 
	 * @param from
	 *            The From field
	 * @param recipient
	 *            A comma separated list of recipient addresses
	 * @param subject
	 *            The Subject field
	 * @param body
	 *            The message text
	 * @param attachments
	 *            If specified a comma separated list of file-paths that will be attached to the message
	 * @return If null, the message was sent. Otherwise, this is the error
	 *         message.
	 * @throws Exception
	 */
	public String sendMail(String from, String recipient, String subject, String body, String attachments) throws Exception {
		try {
			Message message = constructMessage(from, recipient, subject);
			Multipart mp = constructAttachment(body, attachments);
			if (mp != null) {
				message.setContent(mp);
			} else {
				message.setText(body);
			}
			Transport.send(message);
		} catch (MessagingException me) {
			System.err.println(sResHash.getString("USER.FUNCTIONS.SENDMAIL.WARNING", me));
			return me.getMessage();
		}

		return null;
	}

	/**
	 * Sends an email message with ReplyTo field. Make sure the
	 * <i>mail.smtp.host</i> Java property is configured with the hostname of a
	 * valid SMTP server.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 *      var res = system.sendMail(&quot;Sender&quot;,&quot;address1@mail.or,
	 *      		address2@mail.or&quot;,&quot;Subject&quot;,&quot;Message text&quot;,
	 *      		&quot;c\\docs\\myfile.txt&quot;,&quot;my_address@mail.or&quot;);
	 *      if(res != null)
	 *      	task.logmsg(&quot;Error occurred: &quot;+res);
	 * </pre>
	 * 
	 * @param from
	 *            The From field
	 * @param recipient
	 *            A comma separated list of recipient addresses
	 * @param subject
	 *            The Subject field
	 * @param body
	 *            The message text
	 * @param attachments
	 *            If specified a comma separated list of file-paths that will be
	 *            attached to the message
	 * @param replyTo
	 *            A comma separated list of ReplyTo addresses
	 * @return If null, the message was sent. Otherwise, this is the error
	 *         message.
	 * @throws Exception
	 */
	public String sendMail(String from, String recipient, String subject, String body, String attachments, String replyTo)
			throws Exception {
		try {
			Message message = constructMessage(from, recipient, subject);
			// Set Reply To field
			if (replyTo != null && replyTo.length() > 0) {
				StringTokenizer st = new StringTokenizer(replyTo, ",");
				Address[] replyAddress = new Address[st.countTokens()];
				int addressCounter = 0;
				while (st.hasMoreTokens()) {
					replyAddress[addressCounter++] = new InternetAddress(st.nextToken());
				}
				message.setReplyTo(replyAddress);
			}
			Multipart mp = constructAttachment(body, attachments);
			if (mp != null) {
				message.setContent(mp);
			} else {
				message.setText(body);
			}
			Transport.send(message);
		} catch (MessagingException me) {
			System.err.println(sResHash.getString("USER.FUNCTIONS.SENDMAIL.WARNING", me));
			return me.getMessage();
		}

		return null;
	}

	/**
	 * Constructs a Multipart object containing body and attachments
	 * 
	 * @param body
	 *            The message text
	 * @param attachments
	 *            A comma separated list of file-paths that will be attached to
	 *            the message
	 * @return null if no attachments are specified. Otherwise - a Multipart
	 *         object containing the body and the attachments
	 * @throws Exception
	 *             MessagingException - if an error occurs, when constructing
	 *             the Multipart Object
	 * 
	 */
	private Multipart constructAttachment(String body, String attachments) throws Exception {
		if (attachments != null && attachments.length() > 0) {
			Multipart mp = new MimeMultipart();
			MimeBodyPart m1 = new MimeBodyPart();
			m1.setText(body);

			StringTokenizer at = new StringTokenizer(attachments, ",");
			while (at.hasMoreTokens()) {
				String attachment = at.nextToken();
				MimeBodyPart m2 = new MimeBodyPart();
				FileDataSource fds = new FileDataSource(attachment);
				m2.setDataHandler(new DataHandler(fds));
				m2.setFileName(fds.getName());
				mp.addBodyPart(m2);
			}
			mp.addBodyPart(m1);

			return mp;
		}
		return null;
	}

	/**
	 * Constructs an email message, used later by the sendMail methods. Make
	 * sure the <i>mail.smtp.host</i> Java property is configured with the
	 * hostname of a valid SMTP server.
	 * 
	 * @param from
	 *            The From field
	 * @param recipient
	 *            A comma separated list of recipient addresses
	 * @param subject
	 *            The Subject field
	 * @return If null, the message was sent. Otherwise, this is the error
	 *         message.
	 * @throws MessagingException
	 *             If an error occurs, when constructing the Message Object
	 */
	private Message constructMessage(String from, String recipient, String subject) throws MessagingException {
		Properties props = System.getProperties();
		Session session = Session.getDefaultInstance(props, null);
		Message message = new MimeMessage(session);

		message.setFrom(new InternetAddress(from));

		StringTokenizer st = new StringTokenizer(recipient, ",");
		while (st.hasMoreTokens()) {
			message.addRecipient(Message.RecipientType.TO, new InternetAddress(st.nextToken()));
		}

		message.setSubject(subject);
		return message;
	}

	/**
	 * Copy file. This method copies fromPath to toPath. The overwrite flag
	 * specifies whether the destination file should be overwritten.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var fromPath = &quot;c:\\docs\\myfile.txt&quot;;
	 * var toPath = &quot;c:\\backup\\myfile.txt&quot;;
	 * if (!system.copyFile(fromPath, toPath, false))
	 * 	task.logmsg(&quot;Error &quot; + toPath + &quot; file exist!&quot;);
	 * </pre>
	 * 
	 * @param fromPath
	 *            The source file
	 * @param toPath
	 *            The destination file
	 * @param overwrite
	 *            Specify true if destination should be overwritten.
	 * @return true if file was copied, false if toPath exists and
	 *         overwrite=false.
	 * @throws Exception
	 */
	public static boolean copyFile(String fromPath, String toPath, boolean overwrite) throws Exception {
		return FileUtils.copyFile(fromPath, toPath, overwrite);
	}

	/**
	 * Copy file. This method copies fromPath to toPath. The overwrite flag
	 * specifies whether the destination file should be overwritten.
	 * <p>
	 * 
	 * @param fromFile
	 *            The source file
	 * @param toFile
	 *            The destination file
	 * @param overwrite
	 *            Specify true if destination should be overwritten.
	 * @return true if file was copied, false if toPath exists and
	 *         overwrite=false.
	 * @throws Exception
	 */
	public static boolean copyFile(File fromFile, File toFile, boolean overwrite) throws Exception {
		return FileUtils.copyFile(fromFile, toFile, overwrite);
	}

	/**
	 * Copy file. This method copies fromPath to toPath without using buffer.
	 * The overwrite flag specifies whether the destination file should be
	 * overwritten.
	 * 
	 * @param fromPath
	 *            The name of the file to copy
	 * @param toPath
	 *            The name of the new file
	 * @param overwrite
	 *            Specify true if destination should be overwritten.
	 * @return <code>true</code> if copyBinaryFile successed, otherwise
	 *         <code>false</code>.
	 * @deprecated Use {@link #copyFile(String, String)} instead
	 * @throws Exception
	 */
	@Deprecated
	public boolean copyBinaryFile(String fromPath, String toPath, boolean overwrite) throws Exception {
		File fp = new File(fromPath);
		File tp = new File(toPath);

		if (!overwrite && tp.exists())
			return false;

		FileInputStream fis = new FileInputStream(fp);
		try {
			FileOutputStream fos = new FileOutputStream(tp);
			try {
				int ch;
		
				while ((ch = fis.read()) != -1) {
					fos.write(ch);
				}
			} finally {
				fos.close();
			}
		} finally {
			fis.close();
		}

		return true;
	}

	/**
	 * Copy a directory. The recursive flag specifies whether recursion should
	 * be used to copy child directories of <code>target</code>.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var dir1 = &quot;c:\\docs&quot;;
	 * var dir2 = &quot;c:\\backup&quot;;
	 * system.copyDirectory(dir1, dir2, true, true, null);
	 * </pre>
	 * 
	 * @param source
	 *            Source directory
	 * @param target
	 *            Target directory
	 * @param recursive
	 *            Specify true if recursion should be used
	 * @param overwrite
	 *            Specify true if existing files should be overwritten.
	 * @param log
	 *            If not null, log activity to this Log
	 * @throws Exception
	 */
	public void copyDirectory(String source, String target, boolean recursive, boolean overwrite, Log log) throws Exception {
		File src = new File(source);
		File dst = new File(target);

		if ((dst.mkdir()) && (log != null)) {
			log.info(sResHash.getString("USER.FUNCTIONS.CREATEDDIRECTORY.INFO", dst.getAbsolutePath()));
		}

		if (!dst.exists()) {
			throw new Exception(sResHash.getString("USER.FUNCTIONS.CANNOTCREATEDIR.ERROR", dst.getAbsolutePath()));
		}

		String[] list = src.list();
		for (int i = 0; i < list.length; i++) {
			File f1 = new File(src, list[i]);
			File f2 = new File(dst, list[i]);
			if (f1.isDirectory()) {
				if (recursive)
					copyDirectory(f1.getAbsolutePath(), f2.getAbsolutePath(), recursive, overwrite, log);
				else if (log != null) {
					log.info(sResHash.getString("USER.FUNCTIONS.DONTCOPYDIR.INFO", f1.getAbsolutePath()));
				}
			} else if (!overwrite && f2.exists()) {
				if (log != null) {
					log.info(sResHash.getString("USER.FUNCTIONS.DONTOVERWRITEFILE.INFO", f2.getAbsolutePath()));
				}
			} else {
				if (copyFile(f1.getAbsolutePath(), f2.getAbsolutePath(), overwrite) && log != null) {
					log.info(sResHash.getString("USER.FUNCTIONS.CREATEDFILE.INFO", f2.getAbsolutePath()));
				}
			}
		}

	}

	/**
	 * Creates a new Attribute object.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * ocAttr = system.newAttribute(&quot;objectClass&quot;);
	 * ocAttr.addValue(&quot;top&quot;);
	 * ocAttr.addValue(&quot;person&quot;);
	 * ocAttr.addValue(&quot;organizationalPerson&quot;);
	 * ocAttr.addValue(&quot;inetOrgPerson&quot;);
	 * work.setAttribute(ocAttr);
	 * </pre>
	 * 
	 * @param name
	 *            The attribute name
	 * @return The Attribute object
	 */
	public Attribute newAttribute(String name) {
		Attribute a = new Attribute(name);
		return a;
	}

	/**
	 * Creates a new rscSearchCriteira object.
	 * 
	 * @return The SearchCriteria object
	 * 
	 * @see com.ibm.di.server.SearchCriteria
	 */
	public SearchCriteria newSearchCriteria() {
		SearchCriteria rs = new SearchCriteria();
		return rs;
	}

	/**
	 * Creates a new Entry object.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var entry = system.newEntry();
	 * entry.setAttribute(&quot;linenumber&quot;, &quot;1&quot;);
	 * entry.setAttribute(&quot;line&quot;, &quot;Simple line of text!&quot;);
	 * 
	 * write.getConnector().putEntry(entry);
	 * </pre>
	 * 
	 * @return The Entry object
	 * @see #newAttribute(String)
	 */
	public Entry newEntry() {
		return new Entry();
	}

	/**
	 * Creates a new object. This method only works for Java objects that have
	 * empty constructors. It is a convenience method for scripting languages
	 * that cannot create Java objects directly.
	 * 
	 * @param className
	 *            The java class name
	 * @return The newly created object
	 */
	public Object newObject(String className) {
		try {
			Class<?> c = Class.forName(className);
			return c.getDeclaredConstructor().newInstance();
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Throws a SkipEntryException which causes the AssemblyLine to stop the
	 * current cycle and pass control to the currently active Iterator in order
	 * to get the next entry.
	 * <p>
	 * This call bypasses End-of-cycle behaviors, like accumulating (see
	 * TaskCallBlock), committing JDBC operations or persisting Iterator State
	 * for Change Detection Connectors. If you instead wish to stop the current
	 * cycle and still invoke End-of-cycle behaviors, use the
	 * {@link #exitFlow()} call instead.
	 * 
	 * @throws SkipEntryException
	 *             to tell the AssemblyLine to skip the current Entry.
	 * @see #skipTo(String)
	 */
	public void skipEntry() throws com.ibm.di.exceptions.SkipEntryException {
		throw new com.ibm.di.exceptions.SkipEntryException(sResHash.getString("USER.FUNCTIONS.SKIP.ENTRY.EXCEPTION"));
	}

	/**
	 * * Throws a SkipEntryException which causes the AssemblyLine to stop the
	 * current cycle and pass control to the currently active Iterator in order
	 * to get the next entry.
	 * <p>
	 * This call bypasses End-of-cycle behaviors, like accumulating (see
	 * TaskCallBlock), committing JDBC operations or persisting Iterator State
	 * for Change Detection Connectors. If you instead wish to stop the current
	 * cycle and still invoke End-of-cycle behaviors, use the
	 * {@link #exitFlow()} call instead.
	 * 
	 * @param msg
	 *            A message supplied by the user
	 * @throws SkipEntryException
	 *             to tell the AssemblyLine to skip the current Entry.
	 * @see #skipEntry()
	 */
	public void skipEntry(String msg) throws com.ibm.di.exceptions.SkipEntryException {
		throw new com.ibm.di.exceptions.SkipEntryException(msg);
	}

	/**
	 * 
	 * Throws an IgnoreEntryException to tell the AssemblyLine to skip the
	 * current component and continue with the next component in flow.
	 * 
	 * @throws IgnoreEntryException
	 */
	public void ignoreEntry() throws com.ibm.di.exceptions.IgnoreEntryException {
		throw new com.ibm.di.exceptions.IgnoreEntryException(sResHash.getString("USER.FUNCTIONS.SKIP.CONNECTOR.EXCEPTION"));
	}

	/**
	 * Throws an IgnoreEntryException to tell the AssemblyLine to skip the
	 * current component and continue with the next component in flow.
	 * 
	 * @param msg
	 *            A message supplied by the user
	 * @throws IgnoreEntryException
	 */
	public void ignoreEntry(String msg) throws com.ibm.di.exceptions.IgnoreEntryException {
		throw new com.ibm.di.exceptions.IgnoreEntryException(msg);
	}

	/**
	 * Throws a RestartEntryException to tell the AssemblyLine to restart. The
	 * AssemblyLine will continue at the first non-Iterator component in the
	 * AssemblyLine, using the current work object.
	 * 
	 * @throws RestartEntryException
	 */
	public void restartEntry() throws com.ibm.di.exceptions.RestartEntryException {
		throw new com.ibm.di.exceptions.RestartEntryException(sResHash.getString("USER.FUNCTIONS.RESTART.AL.EXCEPTION"));
	}

	/**
	 * Throws a RestartEntryException to tell the AssemblyLine to restart, using
	 * the current work object.
	 * 
	 * @param msg
	 *            A message supplied by the user
	 * @see #restartEntry()
	 * @throws RestartEntryException
	 */
	public void restartEntry(String msg) throws com.ibm.di.exceptions.RestartEntryException {
		throw new com.ibm.di.exceptions.RestartEntryException(msg);
	}

	/**
	 * Throws a RetryEntryException to tell the AssemblyLine to retry this
	 * component. The AssemblyLine will perform the operation of the current
	 * component again, using the current work object.
	 * 
	 * @throws RetryEntryException
	 */
	public void retryEntry() throws com.ibm.di.exceptions.RetryEntryException {
		throw new com.ibm.di.exceptions.RetryEntryException(sResHash.getString("USER.FUNCTIONS.RETRY.CONNECTOR.EXCEPTION"));
	}

	/**
	 * Throws a SkipToException to tell the AssemblyLine to skip to the named
	 * Connector/ScriptComponent.
	 * 
	 * @param name
	 *            The name of the Connector to skip to.
	 * @throws SkipToException
	 */
	public void skipTo(String name) throws com.ibm.di.exceptions.SkipToException {
		throw new com.ibm.di.exceptions.SkipToException(name);
	}

	/**
	 * Throws an AbortALException to instruct the AssemblyLine to terminate. The
	 * AssemblyLine will continue with the Epilog. If the Epilog is already
	 * executed, continue on to the next step (closing Connectors or "Epilog -
	 * After Close").
	 * <p>
	 * If you want your AssemblyLine to terminate gracefully (i.e. not abort),
	 * use one of the following functions system.exitBranch("AssemblyLine") or
	 * task.shutdown() instead.
	 * 
	 * @param reason
	 *            Descriptive text why the AssemblyLine is terminated
	 * @throws AbortALException
	 * @see #exitBranch()
	 */
	public void abortAssemblyLine(String reason) throws com.ibm.di.exceptions.AbortALException {
		throw new com.ibm.di.exceptions.AbortALException(reason);
	}

	/**
	 * Throws a generic java.lang.Exception.
	 * <p>
	 * Whereas the JavaScript throw command allows you to throw a JavaScript
	 * exception, this method creates and throws a {@link Exception} object.
	 * 
	 * @param message
	 *            The message text of the Exception
	 * @throws Exception
	 */
	public void throwException(String message) throws Exception {
		throw new Exception(message);
	}

	/**
	 * Throws an ExitBranchException that tells the AssemblyLine to exit the
	 * current branch/loop.
	 * 
	 * @throws ExitBranchException
	 */
	public void exitBranch() throws com.ibm.di.exceptions.ExitBranchException {
		exitBranch(null);
	}

	/**
	 * Throws an ExitBranchException that tells the AssemblyLine to exit the
	 * named branch/loop. Some special values for name can also be used:<br>
	 * null - exit current (innermost) branch or loop<br>
	 * "Loop" - exit current Loop<br>
	 * "Branch" - exit current branch<br>
	 * "Cycle" - exit this cycle (jump to end of cycle), and begin the next
	 * cycle<br>
	 * "Flow" - jump to end of cycle, and send response if there is a Connector
	 * in Server mode. Then begin the next cycle<br>
	 * "AssemblyLine" - exit dataflow, jump to Epilog
	 * 
	 * @param name
	 *            The name of the branch/loop to exit
	 * @throws ExitBranchException
	 *             to tell the AssemblyLine to exit the named branch/loop
	 */
	public void exitBranch(String name) throws com.ibm.di.exceptions.ExitBranchException {
		throw new com.ibm.di.exceptions.ExitBranchException(name);
	}

	/**
	 * Throws an ExitBranchException that tells the AssemblyLine to not execute
	 * any more of the Flow Section components. In other words, the current
	 * cycle of the AL ends, and in the case of a Server mode Connector, the
	 * Response is carried out.
	 * <p>
	 * This behavior is identical to that caused by the following call:
	 * </p>
	 * <p>
	 * <code>system.exitBranch("Flow");</code>
	 * </p>
	 * 
	 * @throws ExitBranchException
	 *             to tell the AssemblyLine to exit the Flow Section
	 */
	public void exitFlow() throws com.ibm.di.exceptions.ExitBranchException {
		throw new com.ibm.di.exceptions.ExitBranchException("Flow");
	}

	/**
	 * Throws an ExitBranchException that tells the AssemblyLine to not execute
	 * any more of the Flow Section components. In other words, the current
	 * cycle of the AL ends.
	 * <p>
	 * If the skipResponse parameter pass is <code>false</code>, then in the case of
	 * a Server mode Connector, the Response is carried out. If skipResponse is
	 * <code>true</code>, no Response is sent.
	 * </p>
	 * 
	 * @param skipResponse
	 *            Whether or not a Response should be sent if a Server mode
	 *            Connector is feeding this AL.
	 * @throws ExitBranchException
	 *             to tell the AssemblyLine to exit the Flow Section
	 */
	public void exitFlow(boolean skipResponse) throws com.ibm.di.exceptions.ExitBranchException {
		throw new com.ibm.di.exceptions.ExitBranchException(skipResponse ? "Cycle" : "Flow");
	}

	/**
	 * Throws a ContinueloopException to tell the AssemblyLine to continue with
	 * the next value in the loop.
	 * 
	 * @throws ContinueLoopException
	 */
	public void continueLoop() throws com.ibm.di.exceptions.ContinueLoopException {
		throw new com.ibm.di.exceptions.ContinueLoopException();
	}

	/**
	 * Throws a ContinueLoopException to tell the AssemblyLine to continue with
	 * the next value in the named loop.
	 * 
	 * @param name
	 *            The name of the loop
	 * @throws ContinueLoopException
	 */
	public void continueLoop(String name) throws com.ibm.di.exceptions.ContinueLoopException {
		throw new com.ibm.di.exceptions.ContinueLoopException(name);
	}

	/**
	 * Load a Connector Interface from the current Config.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var con = system.loadConnector(&quot;ADChangelogConnectorv2&quot;);
	 * con.initialize(null);
	 * </pre>
	 * 
	 * @param connectorName
	 *            The connector name as it appears in the configuration file
	 * @return The connector object
	 */
	public com.ibm.di.connector.ConnectorInterface loadConnector(String connectorName) {
		try {
			return SystemFunctions.loadConnector(connectorName, getServer());
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Returns the number of milliseconds since Jan 1 1970 as a string.
	 * 
	 * @return Number of milliseconds
	 */
	public String dtSeconds() {
		Date d = new Date();
		String str = Long.toString(d.getTime());
		d = null;
		return str;
	}

	/**
	 * Causes the current thread (e.g. AssemblyLine, etc..) to sleep for a
	 * number of seconds. If the sleep is interrupted the InterruptedException
	 * value is returned. If not, null is returned.
	 * 
	 * @param seconds
	 *            Number of seconds to sleep
	 * @return null if successful, exception object otherwise
	 */
	public InterruptedException sleep(int seconds) {
		try {
			Thread.sleep(seconds * 1000L);
			return null;
		} catch (InterruptedException e) {
			return e;
		}
	}

	/**
	 * Removes occurrences of characters from a string. The method is case
	 * sensitive.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var str = &quot;Some short string&quot;;
	 * var str1 = system.removeStringChars(str, 's');
	 * task.logmsg(&quot;Result: &quot; + str1); //Some hort tring
	 * </pre>
	 * 
	 * @param source
	 *            The source string
	 * @param fromSet
	 *            A string specifying characters to be removed from source
	 * @return The resulting string
	 */
	public String removeStringChars(String source, String fromSet) {
		StringBuffer res = new StringBuffer();

		for (int i = 0; i < source.length(); i++) {
			char ch = source.charAt(i);
			if (fromSet.indexOf(ch) == -1)
				res.append(ch);
		}
		return res.toString();
	}

	/**
	 * Convert A String Into Title Case (Like This), using the current Locale.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var str = &quot;some short string&quot;;
	 * var str1 = system.makeTitleCase(str);
	 * task.logmsg(&quot;Result: &quot; + str1); //Some Short String
	 * </pre>
	 * 
	 * @param in
	 *            The string to convert
	 * @return The converted string
	 */
	public String makeTitleCase(String in) {
		if (in != null) {
			StringBuffer out = new StringBuffer(in.length());
			StringTokenizer tokens = new StringTokenizer(in, "' \t\n\r-", true);
			while (tokens.hasMoreElements()) {
				String token = tokens.nextToken();
				if (token.length() >= 1) {
					out.append(Character.toTitleCase(token.charAt(0)));
				}
				if (token.length() >= 2) {
					out.append(token.substring(1).toLowerCase());
				}
			}
			return out.toString();
		}
		return null;
	}

	/**
	 * Translates characters in a string. The fromSet and toSet contains the
	 * characters used to perform substitution. The first character in fromSet
	 * is replace with the first character in toSet etc.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var str = system.mapString(&quot;Some example text&quot;, &quot;Somexamplt&quot;, &quot;Noneracklg&quot;);
	 * task.logmsg(&quot;Result: &quot; + str); //None erankle gerg
	 * </pre>
	 * 
	 * @param source
	 *            The source string
	 * @param fromSet
	 *            The characters to be replaced
	 * @param toSet
	 *            The characters to replace characters in fromSet
	 * @return The substituted string
	 */
	public String mapString(String source, String fromSet, String toSet) {
		String res = source;

		if (fromSet.length() != toSet.length())
			return source;

		for (int i = 0; i < fromSet.length(); i++) {
			res = res.replace(fromSet.charAt(i), toSet.charAt(i));
		}

		return res;
	}

	/**
	 * Translate a string from one character set to another.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var str = system.translateString(&quot;Some example text&quot;, &quot;UTF-8&quot;, &quot;UTF-16&quot;);
	 * task.logmsg(&quot;Result: &quot; + str);
	 * </pre>
	 * 
	 * @param str
	 *            The source string
	 * @param fromCharset
	 *            The source character set
	 * @param toCharset
	 *            The target character set
	 * @return The translated string
	 */
	public String translateString(String str, String fromCharset, String toCharset) {
		try {
			ByteArrayInputStream bis = new ByteArrayInputStream(str.getBytes());
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			InputStreamReader is;
			OutputStreamWriter os;

			if (fromCharset != null && fromCharset.length() > 0)
				is = new InputStreamReader(bis, fromCharset);
			else
				is = new InputStreamReader(bis);

			if (toCharset != null && toCharset.length() > 0)
				os = new OutputStreamWriter(bos, toCharset);
			else
				os = new OutputStreamWriter(bos);

			int ch;

			while ((ch = is.read()) != -1)
				os.write(ch);

			os.flush();
			return bos.toString();

		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Converts a string to a hexadecimal string where each character is
	 * converted to a two-byte hex value.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var str = system.toHex(&quot;text&quot;);
	 * task.logmsg(&quot;Result: &quot; + str); //74 65 78 74
	 * </pre>
	 * 
	 * @param str
	 *            The source string
	 * @return The hexadecimal string
	 */
	public String toHex(String str) {

		return com.ibm.di.util.StringUtils.toHex(str);
	}

	/**
	 * Returns an attribute value from an X.400 address.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var str = &quot;C=no;ADMD= ;PRMD=uninett;O=sintef;OU=delab;S=Smith;G=John&quot;;
	 * task.logmsg(&quot;Result: &quot; + system.getX400Attribute(str, ';', &quot;PRMD&quot;));
	 * </pre>
	 * 
	 * @param x400
	 *            The X.400 address
	 * @param sep
	 *            The separator used in the address ( typically "/" or ";" )
	 * @param attribute
	 *            The X.400 attribute
	 * @return The value or null if no attribute was found
	 */
	public String getX400Attribute(String x400, String sep, String attribute) {
		StringTokenizer st = new StringTokenizer(x400, sep);

		while (st.hasMoreTokens()) {
			String str = st.nextToken();
			int index = str.indexOf("=");
			if ((index > -1) && (str.substring(0, index).equalsIgnoreCase(attribute))) {
				return str.substring(index + 1);
			}
		}

		return null;
	}

	/**
	 * Converts an X.400 address to a string using short form attribute names.
	 * Attributes are sorted in order of significance.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var str = &quot;C=no;ADMD= ;PRMD=uninett;O=sintef;OU=delab;S=Smith;G=John&quot;;
	 * task.logmsg(&quot;Result: &quot; + system.normalizeX400(str, ';', '/'));
	 * </pre>
	 * 
	 * @param value
	 *            The X.400 address
	 * @param cursep
	 *            The separator used in value
	 * @param newsep
	 *            The separator to be used in the result
	 * @return The reformatted X.400 address
	 */
	public String normalizeX400(String value, String cursep, String newsep) {
		StringTokenizer st = new StringTokenizer(value, cursep);
		Hashtable<String, String> h = new Hashtable<String, String>();

		// System.out.println ("Get X.400 attribute '" + key + "' from '" +
		// value + "'");
		while (st.hasMoreTokens()) {
			String x = st.nextToken();
			// System.out.println ("Check : " + x);

			int index = x.indexOf('=');
			if (index < 0)
				continue;

			h.put(x.substring(0, index).toLowerCase(Locale.ENGLISH), x.substring(index + 1));
		}

		StringBuffer result = new StringBuffer();
		String[] order = { "c", "admd", "prmd", "o", "ou1", "ou2", "ou3", "ou4", "s", "g", "i" };
		String[] order2 = { "c", "a", "p", "o", "ou1", "ou2", "ou3", "ou4", "s", "g", "i" };
		for (int i = 0; i < order.length; i++) {
			String key = h.get(order[i]);
			if (key != null) {
				result.append(order2[i]);
				result.append("=");
				result.append(key);
				result.append(newsep);
			}
		}

		return result.toString();
	}

	/**
	 * Converts a String to a java.util.Date object.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var dateobj = system.parseDate(&quot;23/01/07&quot;, &quot;DD/MM/yy&quot;);
	 * task.logmsg(&quot;Result: &quot; + dateobj); //Tue Jan 23 00:00:00 EET 2007
	 * </pre>
	 * 
	 * @param value
	 *            A string representing date
	 * @param format
	 *            The format of <i>value</i> (e.g. "yyyy.MM.DD", "MM/DD/yy" etc
	 *            ...) A complete list of format characters can be found at
	 *            http://docs.oracle.com/javase/6/docs/api/java/text/SimpleDateFormat.html
	 * @return The Date object or null if an error occurred
	 * @see #lastError
	 */
	public Date parseDate(String value, String format) {
		try {
			return new java.text.SimpleDateFormat(format).parse(value);
		} catch (Exception e) {
			lastError = e;
			try {
				return new SimpleDateFormat(format).parse(value);
			} catch (Exception e2) {
				return null;
			}
		}
	}

	/**
	 * This method formats a java.util.Date object using the provided template.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var d = com.ibm.icu.util.Calendar.getInstance().getTime();
	 * task.logmsg(&quot;Result: &quot; + system.formatDate(d, &quot;dd/MM/yy&quot;));
	 * task.logmsg(&quot;Result: &quot; + system.formatDate(d, &quot;yyyy.MM.dd&quot;));
	 * </pre>
	 * 
	 * @param date
	 *            The date object
	 * @param format
	 *            The format of <i>value</i> (e.g. "yyyy.MM.dd", "MM/dd/yy" etc
	 *            ...) A complete list of format characters can be found at
	 *            http://icu.sourceforge.net/apiref/icu4j/com/ibm/icu/text/
	 *            SimpleDateFormat.html.
	 * @return The string representation or null if an error occurred
	 * @see #lastError
	 */
	public String formatDate(Date date, String format) {
		try {
			SimpleDateFormat df = new SimpleDateFormat(format);
			return df.format(date);
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Splits a string into an array of strings.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var str = &quot;Some short string&quot;;
	 * task.logmsg(&quot;Result: &quot; + system.splitString(str, ' ')); //Some,short,string
	 * </pre>
	 * 
	 * @param source
	 *            The source string
	 * @param separators
	 *            The word-separating characters
	 * @return Array of strings
	 */
	public String[] splitString(String source, String separators) {
		StringTokenizer st = new StringTokenizer(source, separators);
		String res[] = new String[st.countTokens()];
		int i = 0;
		while (st.hasMoreTokens())
			res[i++] = st.nextToken();

		return res;
	}

	/**
	 * Load a connector. This method loads a connector from the current config
	 * file. The call to this method is the same as loadConnector(String).
	 * 
	 * @param name
	 *            The connector name as it appears in the configuration file
	 * @return The connector object
	 * @see #loadConnector(String)
	 */
	public com.ibm.di.connector.ConnectorInterface getConnector(String name) {
		try {
			return SystemFunctions.loadConnector(name, getServer());
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Load a parser Interface from the current Config.
	 * 
	 * @param name
	 *            The parser name as it appears in the configuration file
	 * @return The parser object
	 */
	public com.ibm.di.parser.ParserInterface getParser(String name) {
		try {
			return SystemFunctions.loadParser(name, getServer());
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Use a parser to interpret data. This method will either use the data
	 * object as-is if it is a reader or inputstream class, or it will create a
	 * StringReader from the string representation of the data object and pass
	 * it to the parser. The parser will be called to interpret the byte stream
	 * and return an Entry. If the parse fails a null is returned.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var data = new java.io.FileInputStream(&quot;c:\\docs\\LDIFfile.txt&quot;);
	 * var entry = system.newEntry();
	 * entry = system.parseObject(&quot;LDIFParser&quot;, data);
	 * task.dumpEntry(entry);
	 * </pre>
	 * 
	 * @param parser
	 *            The parser name
	 * @param data
	 *            Any object of type Reader, InputStream or object that has a
	 *            toString method
	 * @return The parsed entry or null if the parser fails
	 * @see #lastError
	 */
	public com.ibm.di.entry.Entry parseObject(String parser, Object data) {
		ParserInterface p = getParser(parser);
		if (p == null)
			return null;

		try {
			if (data instanceof Reader) {
				p.setInputStream((Reader) data);
			} else if (data instanceof InputStream) {
				p.setInputStream((InputStream) data);
			} else {
				p.setInputStream(new StringReader(data.toString()));
			}

			p.initParser();
			return p.readEntry();

		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Executes a shell command.
	 * 
	 * @param command
	 *            A String containing the shell command to execute. This String
	 *            will be parsed with a simple StringTokenizer, to split the
	 *            command and arguments.
	 * @return An ExecuteCommand object
	 * @see ExecuteCommand
	 */
	public ExecuteCommand shellCommand(String command) {
		ExecuteCommand cmd = new ExecuteCommand();
		cmd.exec(command);
		return cmd;
	}

	/**
	 * Executes a shell command with arguments.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 *    myArr = [&quot;-c&quot;, &quot;/bin/ls&quot;, &quot;/mnt/cd rom&quot;];
	 *    cmd = system.shellCommand (&quot;su&quot;, myArr);
	 *    main.logmsg(&quot;The result was:\n&quot; + cmd.getOutputBuffer() );
	 * </pre>
	 * 
	 * @param command
	 *            The shell command to execute
	 * @param args
	 *            The arguments to the command. E.g. a String array containing
	 *            the arguments.
	 * @return An ExecuteCommand object
	 * @see ExecuteCommand
	 */
	public ExecuteCommand shellCommand(String command, Object args) {
		String[] cmdarray;

		if (args instanceof Object[]) {
			Object[] arr = (Object[]) args;
			cmdarray = new String[arr.length + 1];

			for (int i = 0; i < arr.length; i++) {
				cmdarray[i + 1] = (arr[i] == null) ? "" : arr[i].toString();
			}
		} else if (args instanceof Collection<?>) {
			Collection<?> c = (Collection<?>) args;
			cmdarray = new String[c.size() + 1];
			int i = 1;

			for (Object arg : c) {
				cmdarray[i++] = arg.toString();
			}
		} else if (args != null) {
			cmdarray = new String[2];
			cmdarray[1] = args.toString();
		} else {
			cmdarray = new String[1];
		}

		cmdarray[0] = command;

		ExecuteCommand cmd = new ExecuteCommand();
		cmd.exec(cmdarray);
		return cmd;
	}

	/**
	 * Executes a shell command with encoding (codepage).
	 * On some operating systems, e.g. Windows, an issue could
	 * arise because the output from the command is encoded
	 * with an old codepage. This method allows you to specify
	 * the encoding to use when reading the output from the command.
	 * 
	 * @param command
	 *            A String containing the shell command to execute. This String
	 *            will be parsed with a simple StringTokenizer, to split the
	 *            command and arguments.
	 * @param cp The CodePage (encoding) to use
	 * @return An ExecuteCommand object
	 * @see ExecuteCommand
	 */
	public ExecuteCommand shellCommandCP(String command, String cp) {
		ExecuteCommand cmd = new ExecuteCommand();
		cmd.setEncoding(cp);
		cmd.exec(command);
		return cmd;
	}

	/**
	 * Executes a shell command with encoding (codepage) and arguments.
	 * On some operating systems, e.g. Windows, an issue could
	 * arise because the output from the command is encoded
	 * with an old codepage. This method allows you to specify
	 * the encoding to use when reading the output from the command.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 *    myArr = [&quot;-c&quot;, &quot;/bin/ls&quot;, &quot;/mnt/cd rom&quot;];
	 *    cmd = system.shellCommand (&quot;su&quot;, &quot;cp850&quot;, myArr);
	 *    main.logmsg(&quot;The result was:\n&quot; + cmd.getOutputBuffer() );
	 * </pre>
	 * 
	 * @param command
	 *            The shell command to execute
	 * @param cp The CodePage to use
	 * @param args
	 *            The arguments to the command. E.g. a String array containing
	 *            the arguments. 
	 * @return An ExecuteCommand object
	 * @see ExecuteCommand
	 */
	public ExecuteCommand shellCommand(String command, String cp, Object args) {
		String[] cmdarray;

		if (args instanceof Object[]) {
			Object[] arr = (Object[]) args;
			cmdarray = new String[arr.length + 1];

			for (int i = 0; i < arr.length; i++) {
				cmdarray[i + 1] = (arr[i] == null) ? "" : arr[i].toString();
			}
		} else if (args instanceof Collection<?>) {
			Collection<?> c = (Collection<?>) args;
			cmdarray = new String[c.size() + 1];
			int i = 1;

			for (Object arg : c) {
				cmdarray[i++] = arg.toString();
			}
		} else if (args != null) {
			cmdarray = new String[2];
			cmdarray[1] = args.toString();
		} else {
			cmdarray = new String[1];
		}

		cmdarray[0] = command;

		ExecuteCommand cmd = new ExecuteCommand();
		cmd.setEncoding(cp);
		cmd.exec(cmdarray);
		return cmd;
	}

	/**
	 * Returns the name of the operating system.
	 * 
	 * @return The OS name
	 */
	public String getOSName() {
		return System.getProperty("os.name");
	}

	/**
	 * Returns the value for a system property.
	 * 
	 * @param prop
	 *            The property name
	 * @return The property value or null if no such property exists
	 */
	public String getJavaProperty(String prop) {
		return System.getProperty(prop);
	}

	/**
	 * Sets the value of a property name.
	 * 
	 * @param prop
	 *            The property name
	 * @param value
	 *            The property value
	 */
	public void setJavaProperty(String prop, String value) {
		System.setProperty(prop, value);
	}

	/**
	 * Converts an Entry object to an LDIF string. If the passed entry is tagged
	 * with delta codes then the resulting LDIF will be <i>incremental</i>,
	 * reflecting this tagging.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var entry = system.newEntry();
	 * entry.addAttributeValue(&quot;$dn&quot;, &quot;cn=Login Server&quot;);
	 * entry.addAttributeValue(&quot;cn&quot;, &quot;Login Server&quot;);
	 * entry.addAttributeValue(&quot;description&quot;, &quot;Central Authentication Authority&quot;);
	 * entry.addAttributeValue(&quot;objectClass&quot;, &quot;top&quot;);
	 * entry.addAttributeValue(&quot;objectClass&quot;, &quot;applicationProcess&quot;);
	 * task.logmsg(&quot;Result: &quot; + system.entry2LDIF(entry));
	 * </pre>
	 * 
	 * @param e
	 *            The entry
	 * @return The LDIF string
	 * @see #lastError
	 */
	public String entry2LDIF(Entry e) {
		try {
			LDIFParser ldif = new LDIFParser();
			StringWriter sw = new StringWriter();
			ldif.setOutputStream(sw);
			ldif.writeEntry(e);
			return sw.toString();
		} catch (Exception err) {
			lastError = err;
			return null;
		}
	}

	/**
	 * Returns an instance of the FTP object.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var ftpbean = system.getFTP();
	 * ftpbean.connect(&quot;ftp://ftp.myhost.com&quot;, &quot;user&quot;, &quot;pass&quot;);
	 * ftpbean.get(&quot;ftp://ftp.myhost.com/myfile.txt&quot;, &quot;c:\\docs\\myfile.txt&quot;);
	 * </pre>
	 * 
	 * @return The FTP object
	 * @see com.ibm.di.protocols.FTPBean
	 */
	public com.ibm.di.protocols.FTPBean getFTP() {
		return new com.ibm.di.protocols.FTPBean();
	}

	/**
	 * Dumps an entry to the console log. In order to get more verbose
	 * information use the toDeltaString() method of an {@link Entry}.
	 * 
	 * @param e
	 *            The entry object
	 * @see Entry
	 */
	public void dumpEntry(Entry e) {
		((RS) getServer()).getLog().dump(e);
	}

	/**
	 * Returns the Apache XPathAPI
	 * 
	 * @return XPathAPI object
	 */
	public XPathAPI getXPathAPI() {
		return new XPathAPI();
	}

	/**
	 * Selects a single node using an XPath expression from an XML node. For
	 * example if we have the following xml file:
	 * <p>
	 * <code>
	 * &lt;?xml version="1.0" ?&gt;<br>
	 * 	&lt;note&gt;<br>
	 * 		&lt;from&gt;Tony&lt;/from&gt;<br>
	 * 		&lt;to&gt;Michael&lt;/to&gt;<br>
	 * 		&lt;to&gt;John&lt;/to&gt;<br>
	 * 		&lt;heading&gt;Question&lt;/heading&gt;<br>
	 * 		&lt;body&gt;Are you ready?&lt;/body&gt;<br>
	 * 	&lt;/note&gt;
	 * </code>
	 * <p>
	 * Since com.ibm.di.entry.Entry implements the org.w3c.dom.Document to get
	 * the first <code>from</code> node we could use XMLParser to read an Entry
	 * which could be passed to this method as a <code>contextNode</code>
	 * parameter.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var entry = input.getConnector().getNextEntry();
	 * var res = system.selectSingleNode(entry, &quot;note/to&quot;);
	 * task.logmsg(res); // to:Michael
	 * </pre>
	 * 
	 * @param contextNode
	 *            The XML document node
	 * @param str
	 *            The XPath search string
	 * @return XML Document node
	 * @see #lastError
	 */
	public Node selectSingleNode(Node contextNode, String str) {
		try {
			return XPathAPI.selectSingleNode(contextNode, str);
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Selects nodes using an XPath expression from an XML node. For example if
	 * we have the following xml file:
	 * <p>
	 * <code>
	 * &lt;?xml version="1.0" ?&gt;<br>
	 * 	&lt;note&gt;<br>
	 * 		&lt;from&gt;Tony&lt;/from&gt;<br>
	 * 		&lt;to&gt;Michael&lt;/to&gt;<br>
	 * 		&lt;to&gt;John&lt;/to&gt;<br>
	 * 		&lt;heading&gt;Question&lt;/heading&gt;<br>
	 * 		&lt;body&gt;Are you ready?&lt;/body&gt;<br>
	 * 	&lt;/note&gt;
	 * </code>
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var entry = input.getConnector().getNextEntry();
	 * var res = system.selectNodeList(entry, &quot;note/to&quot;);
	 * for (var i = 0; i &lt; res.getLength(); i++) {
	 * 	task.logmsg(res.item(i)); //to:Michael, to:John
	 * }
	 * </pre>
	 * 
	 * @param contextNode
	 *            The XML document node
	 * @param str
	 *            The XPath search string
	 * @return A NodeList object
	 * @see #lastError
	 * @see #selectSingleNode(Node, String)
	 */
	public NodeList selectNodeList(Node contextNode, String str) {
		try {
			return XPathAPI.selectNodeList(contextNode, str);
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Selects nodes using an XPath expression from an XML node. For example if
	 * we have the following xml file:
	 * <p>
	 * <code>
	 * &lt;?xml version="1.0" ?&gt;<br>
	 * 	&lt;note&gt;<br>
	 * 		&lt;from&gt;Tony&lt;/from&gt;<br>
	 * 		&lt;to&gt;Michael&lt;/to&gt;<br>
	 * 		&lt;to&gt;John&lt;/to&gt;<br>
	 * 		&lt;heading&gt;Question&lt;/heading&gt;<br>
	 * 		&lt;body&gt;Are you ready?&lt;/body&gt;<br>
	 * 	&lt;/note&gt;
	 * </code>
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var entry = input.getConnector().getNextEntry();
	 * var iter = system.selectNodeIterator(entry, &quot;note&quot;);
	 * var node;
	 * while (node = iter.nextNode()) {
	 * 	task.logmsg(node);
	 * }
	 * </pre>
	 * 
	 * @param contextNode
	 *            The XML document node
	 * @param str
	 *            The XPath search string
	 * @return A NodeIterator object
	 * @see #lastError
	 * @see #selectSingleNode(Node, String)
	 */
	public NodeIterator selectNodeIterator(Node contextNode, String str) {
		try {
			return XPathAPI.selectNodeIterator(contextNode, str);
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Calls the XSLTransformer to transform an XML document using a given style
	 * sheet."\n" needs to be present in the XSL and XML string for xslTransfrom
	 * to work correctly.
	 * 
	 * @param xsl
	 *            The XSL Style sheet (String, java.io.File, java.io.Reader )
	 * @param xml
	 *            The XML document (String, java.io.File, java.io.Reader )
	 * @return The translated document
	 * @see #lastError
	 */
	public String xslTransform(Object xsl, Object xml) {
		try {
			// Transform
			TransformerFactory transfactory = TransformerFactory.newInstance();
			Transformer transformer;

			ErrorListenerImpl el = new ErrorListenerImpl();
			transfactory.setErrorListener(el);
			transformer = transfactory.newTransformer(getStreamSource(xsl));
			if (el.excep != null)
				throw el.excep;
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			el.excep = null;
			transformer.setErrorListener(el);
			transformer.transform(getStreamSource(xml), new StreamResult(bos));
			String outputencoding = transformer.getOutputProperty("encoding");// Defect
			// 11699

			if (el.excep != null)
				throw el.excep;

			// Defect # 11699
			if (outputencoding != null) {
				return bos.toString(outputencoding);
			} else {
				return bos.toString();
			}
		} catch (Exception error) {
			lastError = error;
			return null;
		}
	}

	/**
	 * Return a StreamSource, if the argument is a filename, String (with
	 * newlines), Reader, InputStream or File
	 * 
	 * @param o
	 *            The Object that is to be used as the source
	 * @return A StreamSource object
	 * @throws IllegalArgumentException
	 *             if the argument is not recognized
	 */
	private StreamSource getStreamSource(Object o) throws IllegalArgumentException {
		if (o instanceof String) {
			if (((String) o).indexOf("\n") != -1)
				return new StreamSource(new StringReader((String) o));
			else
				return new StreamSource(new File((String) o));
		}

		if (o instanceof Reader)
			return new StreamSource((Reader) o);

		if (o instanceof InputStream)
			return new StreamSource((InputStream) o);

		if (o instanceof File)
			return new StreamSource((File) o);

		if (o instanceof StreamSource)
			return (StreamSource) o;

		throw new IllegalArgumentException(sResHash.getString("USER.FUNCTIONS.ARGUMENT.NOT.DOCUMENT", o));
	}

	/**
	 * Dumps the public methods for a Java class.
	 * 
	 * @param className
	 *            The java class name
	 * @return True if dump succeeded
	 * @see #lastError
	 */
	public boolean dumpJavaClass(String className) {
		try {
			Class<?> cls = Class.forName(className);
			dumpJavaClass(cls, System.out, "");
			return true;
		} catch (Exception e) {
			lastError = e;
			return false;
		}
	}

	private boolean dumpJavaClass(Class<?> cls, PrintStream out, String indent) {
		try {
			Method[] m = cls.getDeclaredMethods();
			int i;
			out.println(indent + sResHash.getString("USER.FUNCTIONS.DUMP.JAVA.CLASS.CLASSNAME", cls.getName()));
			for (i = 0; i < m.length; i++) {
				out.print(indent + "\t" + m[i].getName() + " (");
				Class<?>[] p = m[i].getParameterTypes();
				for (int j = 0; j < p.length; j++) {
					if (j > 0)
						out.print(", ");
					out.print(p[j].getName());
				}
				out.println(indent + ");");
			}
			Class<?>[] other = cls.getClasses();
			for (i = 0; i < other.length; i++) {
				out.println(indent + sResHash.getString("USER.FUNCTIONS.DUMP.JAVA.CLASS.SUPERCLASS"));
				dumpJavaClass(other[i], out, indent + "   ");
			}

			return true;
		} catch (Exception e) {
			lastError = e;
			return false;
		}
	}

	/**
	 * Change Java runtime working directory. Sets the "user.dir" property.
	 * 
	 * @param directory
	 *            File system directory
	 * @return True if directory exists, false if directory is not valid
	 */
	public boolean chdir(String directory) {

		File f = new File(directory);
		if (!f.exists())
			return false;

		System.setProperty("user.dir", directory);
		return true;
	}

	/**
	 * Returns the current working directory.
	 * 
	 * @return working directory
	 */

	public String getcwd() {
		return (new File("")).getAbsolutePath();
	}

	/**
	 * Returns the text from the Script Library.
	 * 
	 * @param name
	 *            The script name as it appears in the configuration.
	 * @return The script text or null if not found.
	 */
	public String getScriptText(String name) {
		ScriptConfig tm = SystemFunctions.loadScript(name, getServer());
		if (tm == null)
			return null;
		else
			return tm.getScript();
	}

	/**
	 * Sends an SNMP trap. This method only accepts a String as the value. If
	 * you need to send more complex data use the other snmpTrap() method in
	 * this library.
	 * 
	 * @param host
	 *            The IP host
	 * @param port
	 *            The TCP port
	 * @param oid
	 *            The OID
	 * @param value
	 *            The value
	 * @return True if Trap was sent
	 * @see #lastError
	 */
	public boolean snmpTrap(String host, int port, String oid, String value) {
		try {
			com.ibm.di.protocols.SNMP.sendTrap(host, port, oid, value);
			return true;
		} catch (Exception e) {
			lastError = e;
			return false;
		}
	}

	/**
	 * Sends an SNMP trap. This method allows you to set most of the attributes
	 * of the SNMP trap PDU. If <code>oid</code> is null, <code>value</code>
	 * must be an Entry. All Attribute names will be taken as oids, and the
	 * values of that Attribute will be the corresponding values.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var entry = system.newEntry();
	 * entry.setAttribute(&quot;1.2.3.4.1&quot;, &quot;MyString&quot;);
	 * entry.setAttribute(&quot;1.2.3.4.2&quot;, com.ibm.di.protocols.SNMP.createIPAddress(&quot;10.0.0.1&quot;));
	 * entry.setAttribute(&quot;1.2.3.4.3&quot;, com.ibm.di.protocols.SNMP.createGauge(200));
	 * 
	 * if (!system.snmpTrap(&quot;192.1.1.1&quot;, targetIP, 162, &quot;public&quot;, enterpriseOID, 0, 0, null, entry)) {
	 * 	task.logmsg(&quot;Error sending trap: &quot; + system.lastError);
	 * }
	 * </pre>
	 * 
	 * If oid is non-null, value should be a java.util.Vector, a javascript
	 * array or any other object. The conversion of the values to SNMP PDU
	 * values are as follows: If you provide an object whose class starts with
	 * "com.tivoli.snmp.data" the value is used asis (see
	 * com.ibm.di.protocols.SNMP on how to create these objects). If you provide
	 * an Integer then a com.tivoli.snmp.data.Counter object is created. In all
	 * other cases an OctetString object is created from the object value's
	 * toString() method.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 *     var varBind = [ &quot;MyString&quot;, com.ibm.di.protocols.SNMP.createIPAddress(&quot;10.0.0.1&quot;), com.ibm.di.protocols.SNMP.createGauge(200) ];
	 *     if ( !system.snmpTrap( &quot;192.1.1.1&quot;, targetIP, 162, &quot;public&quot;, enterpriseOID, 0, 0, &quot;1.2.3.4&quot;, varBind) ) {
	 *     	task.logmsg(&quot;Error sending trap: &quot; + system.lastError);
	 *     }
	 * </pre>
	 * 
	 * @param agentIP
	 *            The agent IP address or null to use the local host ip address
	 *            (e.g. InetAddress.getLocalHost().getHostAddress())
	 * @param host
	 *            The target IP host
	 * @param port
	 *            The target TCP port
	 * @param community
	 *            The SNMP community string
	 * @param enterprise
	 *            The Enterprise OID
	 * @param genericTrap
	 *            Trap type: coldStart(0), warmStart(1), linkDown(2), linkUp(3),
	 *            authenticationFailure(4), egpNeighborLoss(5),
	 *            enterpriseSpecific(6)
	 * @param specificTrap
	 *            Used for enterpriseSpecific traps
	 * @param oid
	 *            The OID for the values. If oid is null, value must be an Entry
	 *            where the Attribute names will be used as OIDs
	 * @param value
	 *            The value(s)
	 * @return True if Trap was sent, otherwise check the system.lastError
	 *         object for the exception
	 * @see #lastError
	 */
	public boolean snmpTrap(String agentIP, String host, int port, String community, String enterprise, int genericTrap,
			int specificTrap, String oid, Object value) {
		try {
			com.ibm.di.protocols.SNMP.sendTrap(agentIP, host, port, community, enterprise, genericTrap, specificTrap, oid, value);
			return true;
		} catch (Exception e) {
			lastError = e;
			return false;
		}
	}

	/**
	 * Gets file from a web server. Calling this method is equivalent to calling
	 * httpRequest(&quot;GET&quot;, null, url, null) and both will return
	 * identical results.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var response = system.httpGet(&quot;http://www.mysite.com/files&quot;);
	 * if (response == null) {
	 * 	task.logmsg(&quot;Error getting file: &quot; + system.lastError);
	 * }
	 * </pre>
	 * 
	 * @param url
	 *            Identifies the resource to get from the web server
	 * @return The response from the server is encapsulated into an Entry object
	 *         or NULL if an error occurred.
	 * @see #httpRequest(String, String, String, Object)
	 * @see #lastError
	 */
	public Entry httpGet(String url) {
		return httpRequest("GET", null, url, null);
	}

	/**
	 * Posts file to a web server. This method sends to the server request
	 * message with content type "application/octet-stream". So
	 * <code>file</code> typically will be an application or a document that
	 * must be opened in an application.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 *     var file = &quot;c:\\docs\\myfile.doc&quot;);
	 *     var response =system.httpPost(&quot;http://www.mysite.com/files&quot;,file);
	 *     if (response == null){
	 *      	task.logmsg(&quot;Error posting file: &quot; + system.lastError);
	 *      } else {
	 *      	task.logmsg(&quot;HTTP server response: &quot; + response);
	 *      }
	 * </pre>
	 * 
	 * @param url
	 *            The URL to the web server
	 * @param file
	 *            The file name to be sent. You can provide this parameter as a
	 *            String or as a java.io.File object. If this parameter is NULL
	 *            the method will do as GET with no additional data, otherwise a
	 *            POST is performed.
	 * @return The response from the server is encapsulated into an Entry object
	 *         or NULL if an error occurred.
	 * @see #httpRequest(String, String, String, Object)
	 * @see #lastError
	 */
	public Entry httpPost(String url, Object file) {
		return httpRequest("POST", "application/octet-stream", url, file);
	}

	/**
	 * Sends HTTP Request message to web server. This methods uses HTTPClient
	 * Connector to send request message of type specified by
	 * <code>method</code> to web server at given address <code>url</code>.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var file = new java.lang.FileInputStream(&quot;c:\\docs\\myfile.html&quot;);
	 * var response;
	 * if (file.exist())
	 * 	response = system.httpRequest(&quot;POST&quot;, &quot;text/html&quot;, &quot;http://www.mysite.com/files&quot;, file);
	 * if (response == null) {
	 * 	task.logmsg(&quot;Error sending file: &quot; + system.lastError);
	 * } else {
	 * 	task.logmsg(&quot;HTTP server response: &quot; + response);
	 * }
	 * </pre>
	 * 
	 * @param method
	 *            Type of request method. Possible values: POST, GET, PUT etc.
	 * @param contentType
	 *            Type of the contents.
	 * @param url
	 *            The URL to the web server
	 * @param file
	 *            The body of the request message
	 * @return The response from the server is encapsulated into an Entry object
	 *         or NULL if an error occurred.
	 * @see #httpGet(String)
	 * @see #httpPost(String, Object)
	 */
	public Entry httpRequest(String method, String contentType, String url, Object file) {
		try {
			ConnectorInterface http = getConnector("ibmdi.HTTPClient");
			if (http == null)
				return null;
			http.initialize(null);
			Entry entry = new Entry();
			entry.setAttribute("http.url", url);
			entry.setAttribute("http.method", method);
			if (file != null)
				entry.setAttribute("http.body", file);

			if (contentType != null)
				entry.setAttribute("http.content-type", contentType);

			http.putEntry(entry);
			http.terminate();
			return entry;
		} catch (Exception error) {
			lastError = error;
			return null;
		}
	}

	/**
	 * Converts a ByteArray to a string using platform's default charset. For
	 * example, if you want to set a password(which is sometime a binary value)
	 * you could use this in the attribute mapping.
	 * <p>
	 * <b>Example: </b>
	 * 
	 * <pre>
	 * ret.value = system.arrayToString(work.getObject(&quot;userpassword&quot;));
	 * </pre>
	 * 
	 * @param array
	 *            The byte array to be converted
	 * @return The String object created from byte array
	 */
	public String arrayToString(byte[] array) {
		return new String(array);
	}

	/**
	 * Deletes a file.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var filePath = new java.lang.String(&quot;c:\\docs\\myfile.txt&quot;);
	 * if (!system.deleteFile(filePath))
	 * 	main.logmsg(&quot;Error file &quot; + file + &quot; not deleted!&quot;);
	 * </pre>
	 * 
	 * @param filePath
	 *            The name of the file to be deleted
	 * @return True if file was deleted, false if not deleted or if an error
	 *         occurred
	 * @throws Exception
	 *             if <code>filePath</code> is not a file
	 * @see #lastError
	 */
	public boolean deleteFile(String filePath) throws Exception {
		try {
			File fp = new File(filePath);
			if (fp.isFile())
				return fp.delete();
			else {
				throw new Exception(sResHash.getString("USER.FUNCTIONS.NOTFILE.ERROR", filePath));
			}
		} catch (Exception error) {
			lastError = error;
			return false;
		}
	}

	/**
	 * Rename a file.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var oldName = &quot;c:\\docs\\myfile.txt&quot;;
	 * var newName = &quot;c:\\docs\\newname.txt&quot;;
	 * if (! system.renameFile(oldName, newName) ) {
	 *     //The rename failed. Handle the problem.
	 * }
	 * </pre>
	 * 
	 * @param oldName
	 *            The old name of the file
	 * @param newName
	 *            The new name of the file
	 * @return True if the rename succeeded, false otherwise
	 */
	public boolean renameFile(String oldName, String newName) {
		try {
			File file1 = new File(oldName);
			File file2 = new File(newName);
			return file1.renameTo(file2);
		} catch (Exception error) {
			lastError = error;
			return false;
		}
	}

	/**
	 * Copy a file. <b>Example:</b>
	 * 
	 * <pre>
	 * var oldName = &quot;c:\\docs\\myfile.txt&quot;;
	 * var newName = &quot;c:\\docs\\newname.txt&quot;;
	 * system.copyFile(oldName, newName);
	 * </pre>
	 * 
	 * @param oldFile
	 *            The name of the file to copy
	 * @param newFile
	 *            The name of the new file
	 * @return true if the copying succeeded, false if an exception occurred
	 * @see #lastError
	 */
	public boolean copyFile(String oldFile, String newFile) {
		try {
			BufferedReader in = new BufferedReader(new FileReader(oldFile));
			BufferedWriter out = new BufferedWriter(new FileWriter(newFile));
			int c;

			while ((c = in.read()) != -1)
				out.write(c);

			in.close();
			out.close();
			return true;
		} catch (Exception error) {
			lastError = error;
			return false;
		}
	}

	/**
	 * Create an empty TaskCallBlock.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var tcb = system.newTCB();
	 * 
	 * tcb.setAssemblyLineName(&quot;ALName&quot;);
	 * tcb.setRunMode(com.ibm.di.server.AssemblyLine.RUNMODE_NORMAL); // &quot;normal&quot;
	 * 
	 * var entry = system.newEntry();
	 * entry.setAttribute(&quot;linenumber&quot;, &quot;1&quot;);
	 * entry.setAttribute(&quot;line&quot;, &quot;Simple line of text!&quot;);
	 * tcb.setInitialWorkEntry(entry);
	 * 
	 * var al = main.startAL(tcb);
	 * al.join(); // Wait for called AL to complete
	 * </pre>
	 * 
	 * @return TaskCallBlock object
	 * @see #newEntry()
	 */
	public TaskCallBlock newTCB() {
		return new com.ibm.di.server.TaskCallBlock();
	}

	/**
	 * Create a TaskCallBlock with i/o specifications from an existing
	 * assemblyline. The TCB will contain all input/output parameters as well as
	 * all connectors and their initial parameters and values.
	 * 
	 * @param assemblyLine
	 *            name of the assembly line
	 * @return TaskCallBlock object with i/o specifications from an existing
	 *         assemblyline
	 */
	public TaskCallBlock newTCB(String assemblyLine) {
		try {
			return new com.ibm.di.server.TaskCallBlock(assemblyLine, getServer().getTask(assemblyLine), null);
		} catch (Exception error) {
			lastError = error;
			return null;
		}
	}

	/**
	 * This method retrieves a named object from the default system property
	 * store.
	 * 
	 * @param key
	 *            The unique key
	 * @return Object
	 * @throws Exception
	 */
	public Object getPersistentObject(String key) throws Exception {
		return StoreFactory.getDefaultPropertyStore().getProperty(key);
	}

	/**
	 * This method stores a named object in the default system property store.
	 * 
	 * @param key
	 *            The unique key
	 * @param value
	 *            The object to store (must be java serializable)
	 * @return The old object if any
	 * @throws Exception
	 */
	public Object setPersistentObject(String key, Object value) throws Exception {
		return StoreFactory.getDefaultPropertyStore().setProperty(key, value);
	}

	/**
	 * This method deletes a named object in the default system property store.
	 * 
	 * @param key
	 *            The unique key
	 * @return The old object if any
	 * @throws Exception
	 */
	public Object deletePersistentObject(String key) throws Exception {
		return StoreFactory.getDefaultPropertyStore().removeProperty(key);
	}

	/**
	 * This method returns a Vector containing all AssemblyLines that were
	 * running when the function was called. The example code shows how to print
	 * the names of all running AssemblyLines.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var ral = system.getRunningALs();
	 * var al = new com.ibm.di.server.AssemblyLine();
	 * task.logmsg(&quot;Running ALs:&quot;);
	 * for (var i = 0; i &lt; ral.size(); i++) {
	 * 	al = ral.get(i);
	 * 	task.logmsg(al.getShortName());
	 * }
	 * </pre>
	 * 
	 * @return a java.util.Vector containing the AssemblyLines
	 */
	public static Vector<AssemblyLine> getRunningALs() {
		return Monitor.runningALs();
	}

	/**
	 * This method returns a Vector containing all AssemblyLines with the given
	 * name that were running when the function was called
	 * 
	 * @param name
	 *            Find all AssemblyLines with this name. Only the last part of
	 *            the name (after optional /) is used.
	 * @return a java.util.Vector containing the AssemblyLines
	 */
	public static Vector<AssemblyLine> getRunningALs(String name) {
		if (name == null)
			return Monitor.runningALs();

		int i = name.lastIndexOf('/');
		if (i >= 0)
			name = name.substring(i + 1);

		Vector<AssemblyLine> list = new Vector<AssemblyLine>();

		for (AssemblyLine al : Monitor.runningALs()) {
			String s = al.getName();
			i = s.lastIndexOf('/');
			if (i >= 0)
				s = s.substring(i + 1);
			if (name.equals(s))
				list.add(al);
		}

		return list;
	}

	/**
	 * This method returns a Vector containing all Sequences with the given
	 * name that were running when the function was called
	 * 
	 * @param name
	 *            Find all Sequences with this name. Only the last part of
	 *            the name (after optional /) is used.
	 * @return a java.util.Vector containing the Sequences
	 */
	public static Vector<Sequence> getRunningSequences(String name) {
		if (name == null)
			return Monitor.runningSequences();

		int i = name.lastIndexOf('/');
		if (i >= 0)
			name = name.substring(i + 1);

		Vector<Sequence> list = new Vector<Sequence>();

		for (Sequence seq : Monitor.runningSequences()) {
			String s = seq.getName();
			i = s.lastIndexOf('/');
			if (i >= 0)
				s = s.substring(i + 1);
			if (name.equals(s))
				list.add(seq);
		}

		return list;
	}

	/**
	 * getRsaEncrypted: Obtain encrypted (and ascii-encoded) value for plain
	 * text specified, null strings are not processed and will be returned as
	 * null.
	 * 
	 * @param plainText
	 *            String representing value to be encrypted using public key
	 * @param ksPath
	 *            String representing file path to jks file
	 * @param ksPassword
	 *            String representing password for jks file as specified by path
	 * @param certificateAlias
	 *            String naming the alias of certificate in keystore file
	 * @return String representing encrypted format, null is returned if a null
	 *         is passed in.
	 * @throws java.lang.Exception
	 *             when underlying function fails
	 * @throws Exception
	 */
	public String getRsaEncrypted(String plainText, String ksPath, String ksPassword, String certificateAlias)
			throws java.lang.Exception {
		return IDIPasswordCrypto.encrypt(plainText, ksPath, ksPassword, certificateAlias);

	}

	/**
	 * getRsaDecrypted: Obtain plain ascii text for encrypted ciphertext
	 * specified. Null strings are not processed and will be returned as
	 * received. Empty strings will be encoded/encrypted.
	 * 
	 * @param cipherText
	 *            String representing value to be decrypted using private key
	 * @param ksPath
	 *            String representing file path to jks file
	 * @param ksPassword
	 *            String representing password for jks file as specified by path
	 * @param certificateAlias
	 *            String naming the alias of certificate in keystore file
	 * @param certificatePassword
	 *            String representing password certificate
	 * @return String representing the decrypted format of the received string.
	 *         Null is returned when a null is received.
	 * @throws java.lang.Exception
	 *             when underlying function fails
	 * @throws Exception
	 */
	public String getRsaDecrypted(String cipherText, String ksPath, String ksPassword, String certificateAlias,
			String certificatePassword) throws java.lang.Exception {

		return IDIPasswordCrypto.decrypt(cipherText, ksPath, ksPassword, certificateAlias, certificatePassword);

	}

	/**
	 * Creates an AssemblyLine Pool object from the specified AssemblyLine name.
	 * 
	 * @param assemblyLine
	 *            The name of the assemblyline
	 * @param log
	 *            The Log object to use or null to use the system logger
	 * @return created AssemblyLinePool object
	 * @throws Throwable
	 */
	public AssemblyLinePool createALPool(String assemblyLine, Log log) throws Throwable {
		RS server = (RS) getServer();
		AssemblyLineConfig config = server.getTask(assemblyLine);
		Log logger = (log == null ? server.getLog() : log);
		return new AssemblyLinePool(assemblyLine, logger, server, config);
	}

	/**
	 * Load a Function component Interface from the current Config.
	 * 
	 * @param name
	 *            The name of the function.
	 * @return The Function object
	 * @throws Exception
	 */
	public FunctionInterface getFunction(String name) throws Exception {
		return SystemFunctions.loadFunction(name, getServer());
	}

	/*
	 * Arrays used for base64 encoding/decoding
	 */
	private static int[] decode = { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
		-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 62, -1, -1, -1, 63, 52, 53, 54, 55, 56, 57, 58,
		59, 60, 61, -1, -1, -1, -1, -1, -1, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
		21, 22, 23, 24, 25, -1, -1, -1, -1, -1, -1, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43,
		44, 45, 46, 47, 48, 49, 50, 51, -1, -1, -1, -1, -1 };

	// Initialize encode array
	private static char[] encode = new char[64];
	static {
		for (int i = 0; i < 128; i++) {
			if (decode[i] >= 0)
				encode[decode[i]] = (char) i;
		}
	}

	/**
	 * base64Encode: Obtain Base 64 encoded String from a binary Byte Array
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var e = Array(6);
	 * e[0] = 7;
	 * e[1] = -66;
	 * e[2] = -35;
	 * e[3] = -21;
	 * e[4] = -66;
	 * e[5] = -35;
	 * task.logmsg(&quot;Result: &quot; + system.base64Encode(e)); //B77d677d
	 * </pre>
	 * 
	 * @param b
	 *            byte array containing binary data
	 * @return String containing the base64 encoded representation of the data.
	 */

	public static String base64Encode(byte[] b) {

		StringWriter w = new StringWriter();
		int res = 0;
		int i = 0;
		while (i < b.length) {
			int ch = b[i] & 0xff;
			switch (i % 3) {
			case 0:
				w.write(encode[ch >> 2]);
				res = (ch & 3) << 4;
				break;
			case 1:
				w.write(encode[res | (ch >> 4)]);
				res = (ch & 0xf) << 2;
				break;
			case 2:
				w.write(encode[res | (ch >> 6)]);
				w.write(encode[ch & 0x3f]);
			}
			i++;
		}
		i %= 3;
		if (i != 0) {
			w.write(encode[res]);
			if (i == 1)
				w.write("==");
			else
				w.write("=");
		}
		return w.toString();
	}

	/**
	 * Return the base64 encoding of a String.
	 * @param string The String to encode.
	 * @param encoding Encoding used to convert the String to bytes.
	 * If null, use platform specific encoding.
	 * @return The base64 encoding of the string.
	 * @throws UnsupportedEncodingException If the string cannot be converted
	 * to bytes with the provided encoding.
	 * @since 7.2
	 */
	public static String base64Encode(String string, String encoding) throws UnsupportedEncodingException {
		if (encoding == null)
			return base64Encode(string.getBytes());
		else
			return base64Encode(string.getBytes(encoding));
	}

	/**
	 * base64Decode: Obtain Byte Array from a Base 64 encoded String.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var str = &quot;B77d677d&quot;;
	 * task.logmsg(&quot;Result: &quot; + system.base64Decode(str)); //7,-66,-35,-21,-66,-35
	 * </pre>
	 * 
	 * @param str
	 *            String containing base64 Data.
	 * @return Byte array containing the decoded binary data.
	 */

	public static byte[] base64Decode(String str) {

		ByteArrayOutputStream w = new ByteArrayOutputStream();
		int mode = 0;
		int res = 0;
		for (int i = 0; i < str.length(); i++) {
			int ch = (int) str.charAt(i);
			if (ch > 0 && ch < 128)
				ch = decode[ch];
			else
				continue;

			if (ch < 0)
				continue;

			switch (mode) {
			case 0:
				res = ch << 2;
				break;
			case 1:
				w.write(res | (ch >> 4));
				res = (ch << 4) & 0xff;
				break;
			case 2:
				w.write(res | (ch >> 2));
				res = (ch << 6) & 0xff;
				break;
			case 3:
				w.write(res | ch);
				break;
			}
			mode = (mode + 1) % 4;
		}
		return w.toByteArray();
	}

	/**
	 * Converts a base64 encoded String back to a regular String.
	 * @param str The base64 encoded String
	 * @param encoding Character encoding used to convert bytes to characters.
	 * If null, platform specific encoding is used.
	 * @return The decoded String
	 * @throws UnsupportedEncodingException If the bytes cannot be converted to String=
	 * with the given encoding.
	 * @since 7.2
	 */
	public static String base64Decode(String str, String encoding) throws UnsupportedEncodingException {
		if (encoding == null)
			return new String(base64Decode(str));
		else
			return new String(base64Decode(str), encoding);
	}

	/**
	 * encodeToHexstring: Obtain HexString from a byte array.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var e = new Array(4);
	 * e[0] = 7;
	 * e[1] = -66;
	 * e[2] = -35;
	 * e[3] = -21;
	 * task.logmsg(&quot;Result: &quot; + system.encodeToHexstring(e)); // \07\be\dd\eb
	 * </pre>
	 * 
	 * @param data
	 *            byte array containing binary data
	 * @return String containing the Hexadecimal representation of the data.
	 */

	public static String encodeToHexstring(byte[] data) {
		StringBuffer encodestr = new StringBuffer();
		for (int i = 0; i < data.length; i++) {
			encodestr.append("\\");
			String hexstr = Integer.toHexString((int) data[i]);
			int hexstrlen = 2;
			if (hexstr.length() < 2)
				hexstr = "0" + hexstr;
			else
				hexstrlen = hexstr.length();
			encodestr.append(hexstr.substring(hexstrlen - 2));
		}
		return encodestr.toString();
	}

	/**
	 * Creates an IDispatch automation object. This method creates a new
	 * COMProxy object and then calls new IDispatch(progID) on it.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var cominst = system.createCOMInstance(&quot;Word.Basic&quot;);
	 * </pre>
	 * 
	 * @param progID
	 *            the progID (Programmatic IDentifier)is a string that uniquely
	 *            identifies the COM object, stored in the registry and is of
	 *            the form: Project.ClassName
	 * @return IDispatch object, null if not running under Windows
	 */
	public static IDispatch createCOMInstance(String progID) {
		try {
			return COMProxy.create().createInstance(progID);
		} catch (Exception err) {
			return null;
		}
	}

	/**
	 * This method create a new Memory Buffer Queue if it does not already
	 * exist. If the pipe already exists with the specified instaName and
	 * pipeName then a handle to the same pipe is returned. Paging is disabled
	 * in this case.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var con = input.getConnector();
	 * var pipe = system.newPipe(null, &quot;new_pipe&quot;, 2);
	 * 
	 * var entry1 = con.getNextEntry();
	 * pipe.write(entry1);
	 * 
	 * var entry2 = con.getNextEntry();
	 * pipe.write(entry2);
	 * </pre>
	 * 
	 * @param instName
	 *            name of the instance. Default instance will be used if this
	 *            param is null.
	 * @param pipeName
	 *            name of the pipe to be created
	 * @param watermark
	 *            With Paging On, it is the threshold at which objects are
	 *            persisted to the System Store With Paging Off, it is the
	 *            maximum queue size
	 * @return MemBufferQ
	 * @throws Exception
	 */
	@SuppressWarnings("deprecation")
	public static MemBufferQ newPipe(String instName, String pipeName, int watermark) throws Exception {
		return MemBufferQFactory.getInstance(instName).newPipe(pipeName, watermark);
	}

	/**
	 * This method create a new Memory Buffer Queue if it does not already
	 * exist. If the pipe already exists with the specified instaName and
	 * pipeName then a handle to the same pipe is returned. Paging is enabled in
	 * this case.
	 * 
	 * @param instName
	 *            name of the instance. Default instance will be used if this
	 *            param is null.
	 * @param pipeName
	 *            name of the pipe to be created
	 * @param watermark
	 *            With Paging On, it is the threshold at which objects are
	 *            persisted to the System Store With Paging Off, it is the
	 *            maximum queue size
	 * @param pagesize
	 * @return MemBufferQ
	 * @throws Exception
	 */
	public static MemBufferQ newPipe(String instName, String pipeName, int watermark, int pagesize) throws Exception {
		return MemBufferQFactory.getInstance(instName).newPipe(pipeName, watermark, pagesize);
	}

	/**
	 * This method returns a handle to a pipe with the specified instName and
	 * pipeName (if it already exists). If the pipe does not exist, then this
	 * method throws an Exception.
	 * 
	 * @param instName
	 *            name of the instance. Default instance will be used if this
	 *            param is null.
	 * @param pipeName
	 *            name of the pipe to be returned
	 * @return MemBufferQ
	 * @throws Exception
	 */
	public static MemBufferQ getPipe(String instName, String pipeName) throws Exception {
		return MemBufferQFactory.getInstance(instName).getPipe(pipeName);
	}

	/**
	 * Deletes the specified pipe from the specified instance. Drops the
	 * associated table in System Store with the specified memory queue (if it's
	 * a persistent queue). This method throws an exception if the pipe name is
	 * invalid or does not exist.
	 * 
	 * @param instName
	 *            name of the instance. Default instance will be used if this
	 *            param is null.
	 * @param pipeName
	 *            name of the pipe to be deleted
	 * @throws Exception
	 */
	public static void deletePipe(String instName, String pipeName) throws Exception {
		MemBufferQFactory.getInstance(instName).deleteQueue(pipeName);
	}

	/**
	 * Deletes specified pipe from default instance Drops the associated table
	 * in System Store with the specified memory queue (if it's a persistent
	 * queue). This method throws an exception if the pipe name is invalid or
	 * does not exist.
	 * 
	 * @param pipeName
	 *            name of the pipe to be deleted
	 * @throws Exception
	 */

	public static void deletePipe(String pipeName) throws Exception {
		deletePipe(null, pipeName);
	}

	/**
	 * Get external property using delegator object.
	 * <p>
	 * Note that the {@link #getTDIProperty(String)} method is recommended over
	 * this older version.
	 * 
	 * @param propName
	 * @return external property
	 * @throws Exception
	 * @deprecated use {@link #getTDIProperty(String)} instead
	 */
	@Deprecated
	public Object getExternalProperty(String propName) throws Exception {
		return getExternalProperty(null, propName);
	}

	/**
	 * Set external property using delegator object.
	 * 
	 * @param propName
	 * @param value
	 * @throws Exception
	 * @deprecated use {@link #setTDIProperty(String, Object)} instead
	 */
	@Deprecated
	public void setExternalProperty(String propName, Object value) throws Exception {
		setExternalProperty(null, propName, value);
	}

	/**
	 * Get external property from specific extprop object.
	 * <p>
	 * Note that the {@link #getTDIProperty(String, String)} method is
	 * recommended over this older version.
	 * 
	 * @param extObj
	 * @param propName
	 * @return external property
	 * @throws Exception
	 * @deprecated use {@link #getTDIProperty(String, String)} instead
	 *             Implementation of the method is changed due to defect 12968
	 */
	@Deprecated
	public Object getExternalProperty(String extObj, String propName) throws Exception {
		if (getServer() == null)
			return null;

		if (extObj == null) {
			return getTDIProperties().getProperty(propName);
		} else {
			return getTDIProperties().getProperty(extObj, propName);
		}
		// return getExtProp(extObj).getParameter(propName);
	}

	/**
	 * Set external property in a specific extprop object
	 * 
	 * @param extObj
	 *            The external object containing properties
	 * @param propName
	 *            The property name to set
	 * @param value
	 *            The property value to set
	 * @throws Exception if the operation fails
	 * @deprecated use {@link #setTDIProperty(String, String, Object)} instead
	 */
	@Deprecated
	public void setExternalProperty(String extObj, String propName, Object value) throws Exception {
		getExtProp(extObj).setParameter(propName, value);
	}

	/**
	 * Returns a named extprop object.
	 * <p>
	 * Note that the {@link #getTDIProperty(String)} and
	 * {@link #getTDIProperty(String, String)} methods are recommended over this
	 * older version.
	 * 
	 * @param name
	 *            name of the extprop
	 * @return ExternalPropertiesConfig object
	 * @throws Exception
	 * @deprecated use {@link #getTDIProperties()} instead
	 */
	@Deprecated
	public ExternalPropertiesConfig getExtProp(String name) throws Exception {
		if (getServer() == null)
			return null;

		if (name == null)
			return getServer().getMetamergeConfig().getExternalProperties();
		else
			return (ExternalPropertiesConfig) getServer().getMetamergeConfig().lookup(
					MetamergeConfig.DEFAULT_EXTPROP_FOLDER + "/" + name);
	}

	/**
	 * Generates the hexadecimal String representation of an Active Directory
	 * GUID based on its 128-bit binary representation. The String
	 * representation of a GUID has the form
	 * "{xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx}". The digits used are the
	 * hexadecimal digits 0,1,2,3,4,5,6,7,8,9,A,B,C,D,E and F.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var e = new Array(4);
	 * 
	 * e[0] = 0xd0;
	 * e[8] = 0x8a;
	 * e[1] = 0xef;
	 * e[9] = 0x94;
	 * e[2] = 0x68;
	 * e[10] = 0xb1;
	 * e[3] = 0x8e;
	 * e[11] = 0xc1;
	 * e[4] = 0xbe;
	 * e[12] = 0x46;
	 * e[5] = 0x1a;
	 * e[13] = 0x85;
	 * e[6] = 0x5c;
	 * e[14] = 0xbe;
	 * e[7] = 0x40;
	 * e[15] = 0xd7;
	 * 
	 * task.logmsg(&quot;Result: &quot; + system.binaryGUIDtoString(e)); //{8E68EFD0-1ABE-405C-8A94-B1C14685BED7}
	 * </pre>
	 * 
	 * @param binaryData
	 *            a 16-byte byte array, holding the 128-bit binary
	 *            representation of the GUID.
	 * @return The hexadecimal String representation of the binary GUID.
	 */
	public String binaryGUIDtoString(byte[] binaryData) {
		if (binaryData == null || binaryData.length != 16) {
			return null;
		}

		StringBuffer stringGUID = new StringBuffer("{");
		String str;
		int n;

		// generate the first 8 hexadecimal digits
		n = 0;
		n |= ((int) (binaryData[0])) & 0x000000FF;
		n |= (((int) (binaryData[1])) << 8) & 0x0000FF00;
		n |= (((int) (binaryData[2])) << 16) & 0x00FF0000;
		n |= (((int) (binaryData[3])) << 24) & 0xFF000000;
		str = java.lang.Integer.toHexString(n);
		stringGUID.append(insertLeadingZeros(str, 8));
		stringGUID.append("-");

		// generate the first and the second groups of 4 hexadecimal digits
		for (int i = 2; i < 4; i++) {
			n = 0;
			n |= ((int) (binaryData[i * 2])) & 0x000000FF;
			n |= (((int) (binaryData[i * 2 + 1])) << 8) & 0x0000FF00;
			str = java.lang.Integer.toHexString(n);
			stringGUID.append(insertLeadingZeros(str, 4));
			stringGUID.append("-");
		}

		// generate the third group of 4 hexadecimal digits
		n = 0;
		n |= (((int) (binaryData[8])) << 8) & 0x0000FF00;
		n |= (((int) (binaryData[9]))) & 0x000000FF;
		str = java.lang.Integer.toHexString(n);
		stringGUID.append(insertLeadingZeros(str, 4));
		stringGUID.append("-");

		// generate the last 12 hexadecimal digits
		for (int i = 5; i < 8; i++) {
			n = 0;
			n |= (((int) (binaryData[i * 2 + 1]))) & 0x000000FF;
			n |= (((int) (binaryData[i * 2])) << 8) & 0x0000FF00;
			str = java.lang.Integer.toHexString(n);
			stringGUID.append(insertLeadingZeros(str, 4));
		}

		stringGUID.append("}");

		return stringGUID.toString().toUpperCase();
	}

	/**
	 * Inserts leading zeros. For example, if the number 21 is required to be
	 * represented by exactly 4 digits, then the call "insertLeadingZeros("21",
	 * 4)" will return "0021". If the number is already represented by the
	 * required number of digits or more, no leading zeros will be inserted.
	 * 
	 * @param strNumber
	 *            the String representation of a number.
	 * @param requiredDigits
	 *            the number of digits required to represent the number.
	 * @return The String representation of the number with the necessary number
	 *         of leading zeros.
	 */
	private String insertLeadingZeros(String strNumber, int requiredDigits) {
		String result = strNumber;
		while (result.length() < requiredDigits) {
			result = "0" + result;
		}
		return result;
	}

	/**
	 * Removes invalid XML chars.
	 * 
	 * @param aString
	 *            string to clean
	 * @return cleaned string
	 */
	public static String removeInvalidXMLChars(String aString) {

		if (aString == null) {
			return null;
		}

		StringBuffer cleanXML = new StringBuffer(aString);

		for (int i = cleanXML.length() - 1; i > -1; i--) {
			for (int j = 0; j < INVALID_XML_CHARS.length; j++) {
				if (cleanXML.charAt(i) == INVALID_XML_CHARS[j]) {
					cleanXML.deleteCharAt(i);
					break;
				}
			}
		}

		return cleanXML.toString();
	}

	/**
	 * Dynamically add jar file containing class definitions. TDI has a loader
	 * that finds all classes in jar files in the jars directory of the
	 * installation folder. If you want to dynamically add additional jar files,
	 * you can use this method. An alternative to dynamically loading additional
	 * jar files, is to set the "com.ibm.di.loader.userjars" property in
	 * global.properties.
	 * 
	 * @param path
	 *            The full path name of a jar file or a directory containing jar
	 *            files
	 * @see com.ibm.di.loader.IDILoader#addFiles(String)
	 * 
	 */
	public static void loadJarFile(String path) {
		ClassLoader loader = UserFunctions.class.getClassLoader();
		if (loader instanceof IDILoader) {
			((IDILoader) loader).addFiles(path);
			ScriptEngineOptions.clearNoClassSet();			
		}
	}

	/**
	 * Returns the TDIProperties object for the current configuration
	 * 
	 * @return TDIProperties object
	 * @throws Exception
	 */
	public TDIProperties getTDIProperties() throws Exception {
		if (getServer() == null)
			return null;
		else
			return getServer().getMetamergeConfig().getTDIProperties();
	}

	/**
	 * Returns the value for a TDI property
	 * 
	 * @param name
	 *            The name of the property
	 * @return TDI property value
	 * @throws Exception
	 */
	public Object getTDIProperty(String name) throws Exception {
		return getTDIProperties().getProperty(name);
	}

	/**
	 * Returns the property value from a specific TDI property store
	 * 
	 * @param propstore
	 *            The property store name
	 * 
	 * @param name
	 *            The name of the property
	 * @return TDI property value
	 * @throws Exception
	 */
	public Object getTDIProperty(String propstore, String name) throws Exception {
		return getTDIProperties().getProperty(propstore, name);
	}

	/**
	 * Sets the property value for a property (store selection based on naming
	 * rules and order).
	 * 
	 * @param name
	 *            The name of the property
	 * @param value
	 *            The property value
	 * @throws Exception
	 */
	public void setTDIProperty(String name, Object value) throws Exception {
		getTDIProperties().setProperty(name, value);
	}

	/**
	 * Sets the property value in a specific TDI property store
	 * 
	 * @param propstore
	 *            The property store name
	 * @param name
	 *            The name of the property
	 * @param value
	 *            The property value
	 * @throws Exception
	 */
	public void setTDIProperty(String propstore, String name, Object value) throws Exception {
		getTDIProperties().setProperty(propstore, name, value);
	}

	/**
	 * Returns true if the first String starts with the second String, ignoring
	 * case. If at least one if the Strings are null, returns false. This method
	 * is case insensitive.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var str1 = &quot;IBM Corporation&quot;;
	 * var str2 = &quot;ibm&quot;;
	 * if (system.startsWithIC(str1, str2)) {
	 * 	task.logmsg(&quot;str1 starts with str2&quot;);
	 * } else {
	 * 	task.logmsg(&quot;str1 does not start with str2&quot;);
	 * }
	 * </pre>
	 * 
	 * @param first
	 *            The first String
	 * @param second
	 *            The second String
	 * @return true if and only if the first String starts with the second
	 *         String, ignoring case
	 * @since 6.1.1
	 */
	public static boolean startsWithIC(String first, String second) {
		if (first == null || second == null)
			return false;
		return first.regionMatches(true, 0, second, 0, second.length());
	}

	/**
	 * Returns true if the first String ends with the second String, ignoring
	 * case If at least one if the Strings are null, returns false.This method
	 * is case insensitive.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var str1 = &quot;Directory Integrator&quot;;
	 * var str2 = &quot;Rator&quot;;
	 * if (system.endsWithIC(str1, str2)) {
	 * 	task.logmsg(&quot;str1 ends with str2&quot;);
	 * } else {
	 * 	task.logmsg(&quot;str1 does not end with str2&quot;);
	 * }
	 * </pre>
	 * 
	 * @param first
	 *            The first String
	 * @param second
	 *            The second String
	 * @return true if and only if the first String ends with the second String,
	 *         ignoring case
	 * @since 6.1.1
	 */
	public static boolean endsWithIC(String first, String second) {
		if (first == null || second == null)
			return false;
		if (first.length() < second.length())
			return false;
		return first.substring(first.length() - second.length()).equalsIgnoreCase(second);
	}

	/**
	 * Returns true if the second String is a substring of the first, ignoring
	 * case. If at least one if the Strings are null, returns false. Examples:
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * system.containsIC(&quot;abcde&quot;, &quot;BCD&quot;); // Returns true
	 * system.containsIC(&quot;abcde&quot;, &quot;bd&quot;); // Returns false
	 * </pre>
	 * 
	 * @param first
	 *            The first String
	 * @param second
	 *            The second String
	 * @return true if and only if the first String contains the second String,
	 *         ignoring case
	 * @since 6.1.1
	 */
	public static boolean containsIC(String first, String second) {
		if (first == null || second == null)
			return false;
		int n = second.length();
		for (int i = 0, end = first.length() - n; i <= end; i++) {
			if (first.substring(i, i + n).equalsIgnoreCase(second))
				return true;
		}
		return false;
	}

	/**
	 * Returns a ParameterSubstitution object using the given pattern. For
	 * example if we have the following file:
	 * <p>
	 * <i>&quot; John 62-58-99<br>
	 * Lily 056/6563425<br>
	 * Michael +359 88 540 90&quot;<br>
	 * </i>
	 * <p>
	 * And read this file into two fields called 'name' and 'phone' you could
	 * print the information by this way:
	 * 
	 * <pre>
	 * expression = system.getTDIExpression(&quot;{work.name}'s number is {work.phone}.&quot;);
	 * map = new java.util.HashMap();
	 * map.put(&quot;mc&quot;, main.getMetamergeConfig());
	 * 
	 * while ((work = input.getConnector().getNextEntry()) != null) {
	 * 	map.put(&quot;work&quot;, work);
	 * 	task.logmsg(expression.substitute(map)); // John's number is 62-58-99. and so on...
	 * }
	 * </pre>
	 * 
	 * @param pattern
	 *            The pattern to use for substitution.
	 * @return A ParameterSubstitution with the given pattern
	 * @throws Exception
	 * @see #substitute(String, Map)
	 */
	public static ParameterSubstitution getTDIExpression(String pattern) throws Exception {
		return new ParameterSubstitution(pattern);
	}

	/**
	 * Performs a one-time parsing and substitution of pattern with the objects
	 * available in params. This method uses a Map object where you provide the
	 * available objects for pattern expansion.
	 * <p>
	 * You should at least provide "mc=MetamergeConfig" or
	 * "config=BaseConfiguration" object, otherwise expansion of TDI-properties
	 * will not work. If you want to expand AL component parameters, you need to
	 * provide a "config=BaseConfiguration" object.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * map = new java.util.HashMap();
	 * map.put(&quot;mc&quot;, main.getMetamergeConfig());
	 * map.put(&quot;work&quot;, work);
	 * result = system.substitute(&quot;{work.cn} {property.myprop}&quot;, map);
	 * </pre>
	 * 
	 * @param pattern
	 *            The pattern string to expand
	 * @param params
	 *            The available objects (e.g. conn, work, task etc)
	 * @return The expanded string
	 * @throws Exception
	 */
	public static String substitute(String pattern, Map<String, Object> params) throws Exception {
		return new ParameterSubstitution(pattern).substitute(params);
	}

	/**
	 * Performs a one-time parsing and substitution of pattern with named
	 * objects. You should at least provide "mc=MetamergeConfig" or
	 * "config=BaseConfiguration" objects, otherwise expansion of TDI-properties
	 * will not work. If you want to expand AL component parameters, you need to
	 * provide a "config=BaseConfiguration" object.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 *    result = system.substitute(&quot;{work.cn} {property.myprop}&quot;, [&quot;mc&quot;, &quot;work&quot;], [main.getMetamergeConfig(), work]);
	 * </pre>
	 * 
	 * @param pattern
	 *            The pattern string to expand
	 * @param names
	 *            The names of the available objects (e.g. "conn", "work",
	 *            "task" etc)
	 * @param objects
	 *            The available objects (e.g. conn, work, task etc)
	 * @return The expanded string
	 * @throws Exception
	 */
	public static String substitute(String pattern, String[] names, Object[] objects) throws Exception {
		HashMap<String, Object> map = new HashMap<String, Object>();
		if (names != null && objects != null) {
			for (int i = 0; i < names.length; i++) {
				map.put(names[i], (i < objects.length ? objects[i] : null));
			}
		}
		return new ParameterSubstitution(pattern).substitute(map);
	}
	
	/**
	 * Returns the backtrace for a throwable.
	 * @param t - The Throwable
	 * @return A string representation of the backtrace.
	 * @since 7.2
	 */
	public String getStackTrace(Throwable t) {
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		t.printStackTrace(pw);
		pw.close();
		return sw.toString();		
	}
	
	/**
	 * Returns all bytes in the file as a byte array.
	 * @param fileName Name of the file to read
	 * @return The bytes contained in the file as a byte[]
	 * @throws IOException If the file is not found or not readable
	 * @since SDI 7.2 FP0004
	 */
	public byte[] readBytes(String fileName) throws IOException {
		FileInputStream fis = new FileInputStream(fileName);
		try {
			return FileUtils.readInputStream(fis);
		} finally {
			fis.close();
		}
	}
	
	public static int getNoOperationsPayload(String jsonStr){
		ObjectMapper objectMapper = new ObjectMapper();
		int count=0;
		try {
            // Parse JSON into JsonNode
            JsonNode rootNode = objectMapper.readTree(jsonStr);
			JsonNode opNode = rootNode.path("Operations");			
			if (opNode.isArray()) {
				count = opNode.size(); 
				System.out.println("misever:No.of operations = "+count);
			}			
        } catch (IOException e) {
            e.printStackTrace();
        }		
		return count;
	}
	
	/** @deprecated Internal utility method for JSON payload parsing. */
	public static String getmethodPayload(String jsonStr, int index)
	{
		ObjectMapper objectMapper = new ObjectMapper();
		int count=0;
		String method=null;
		try {
            // Parse JSON into JsonNode
            JsonNode rootNode = objectMapper.readTree(jsonStr);
			JsonNode opNode = rootNode.path("Operations");			
			if (opNode.isArray()) {
				count = opNode.size(); 
			}		
			JsonNode cNode;
			for (int i=0;i<count;i++){
				if (i == index){
					cNode = opNode.get(i).path("method");
					method=cNode.toString();
					System.out.println("misever:Method="+method);
				}
			}	
        } catch (IOException e) {
            e.printStackTrace();
        }		
		return method;
	}
	
	/** @deprecated Internal utility method for JSON payload parsing. */
	public static String getpathPayload(String jsonStr, int index)
	{
		ObjectMapper objectMapper = new ObjectMapper();
		int count=0;
		String path=null;
		try {
            // Parse JSON into JsonNode
            JsonNode rootNode = objectMapper.readTree(jsonStr);
			JsonNode opNode = rootNode.path("Operations");			
			if (opNode.isArray()) {
				count = opNode.size(); 
			}		
			JsonNode cNode;
			for (int i=0;i<count;i++){
				if (i == index){
					cNode = opNode.get(i).path("path");
					path=cNode.toString();
					System.out.println("misever:Path="+path);
				}
			}	
        } catch (IOException e) {
            e.printStackTrace();
        }		
		return path;
	}
	
	/** @deprecated Internal utility method for JSON payload parsing. */
	public static String getdataPayload(String jsonStr, String nodeName, int index)
	{
		ObjectMapper objectMapper = new ObjectMapper();
		int count=0;
		String data=null;
		try {
            // Parse JSON into JsonNode
            JsonNode rootNode = objectMapper.readTree(jsonStr);
			JsonNode opNode = rootNode.path("Operations");			
			if (opNode.isArray()) {
				count = opNode.size(); 
			}		
			JsonNode cNode;
			for (int i=0;i<count;i++){
				if (i == index){
					cNode = opNode.get(i).path(nodeName);
					data=cNode.toString();
					System.out.println("misever:Data="+data);
				}
			}	
        } catch (IOException e) {
            e.printStackTrace();
        }		
		return data;
	}
	
	/** @deprecated Internal utility method for JSON payload parsing. */
	public static String getpatchopPayload(String jsonStr, int index)
	{
		ObjectMapper objectMapper = new ObjectMapper();
		int count=0;
		String op=null;
		try {
            // Parse JSON into JsonNode
            JsonNode rootNode = objectMapper.readTree(jsonStr);
			JsonNode opNode = rootNode.path("Operations");			
			if (opNode.isArray()) {
				count = opNode.size(); 
			}		
			JsonNode cNode;
			for (int i=0;i<count;i++){
				if (i == index){
					cNode = opNode.get(i).path("op");
					op=cNode.toString();
					System.out.println("op="+op);
				}
			}	
        } catch (IOException e) {
            e.printStackTrace();
        }		
		return op;
	}
	
	/** @deprecated Internal utility method for JSON payload parsing. */
	public static String getpatchattrValuePayload(String jsonStr, String attrName, int index)
	{
		
		ObjectMapper objectMapper = new ObjectMapper();
		String var1,result,attrValue=null;
		JsonNode cNode,dNode,eNode;
			
		try{
			// Parse JSON into JsonNode
			JsonNode rootNode = objectMapper.readTree(jsonStr);
			JsonNode aNode = rootNode.path("Operations");
			System.out.println("array size="+aNode.size());
			for (int i=0;i<aNode.size();i++){
				// Note: aNode.get(i).path("op") was assigned to bNode but never used
				cNode = aNode.get(i).path("path");
				var1=cNode.toString();
				result = var1. replaceAll("\"", "");
				System.out.println("read attribute name from json="+cNode.toString());
				System.out.println("received attribute from function= "+attrName);
				if (result.equals(attrName)){
				dNode = aNode.get(i).path("value");
				//System.out.println("array size="+dNode.size());
				//for (int j=0;j<dNode.size();j++){		
					eNode = dNode.get(0).path(result);
					attrValue = eNode.toString();
					System.out.println("misever:attribute value="+attrValue);
				//}
				break;
				}//end of if
				
			}
		}catch (Exception e) {
            System.out.println("catching exception");
			e.printStackTrace();	
		}
		return attrValue;
	}
	// ========================================================================
	// IBM JavaScript Long Precision Handling Methods
	// ========================================================================
	// The IBM JavaScript engine stores all numbers as IEEE 754 doubles with
	// ~53-bit precision. Java long values (64-bit) lose precision when converted.
	// These methods handle long values as strings to preserve precision.
	// ========================================================================

	/**
	 * Converts a string representation of a long value to a Java Long object.
	 * This method preserves precision for values that exceed JavaScript's
	 * 53-bit number precision limit (2^53 = 9,007,199,254,740,992).
	 * <p>
	 * <b>Background:</b> The IBM JavaScript engine stores all numbers as IEEE 754
	 * doubles, which have approximately 53 bits of precision. Java long values
	 * are 64-bit integers. When long values greater than 2^53 are passed from
	 * JavaScript to Java, they lose precision. This method allows you to pass
	 * long values as strings to preserve their exact value.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * // JavaScript - pass large ID as string to avoid precision loss
	 * var userId = &quot;9223372036854775807&quot;;  // Max long value
	 * var longId = system.toLong(userId);
	 * 
	 * // Use in Java method calls
	 * connector.lookup(longId);
	 * </pre>
	 * 
	 * @param str
	 *            The string representation of the long value. Leading and trailing
	 *            whitespace is automatically trimmed.
	 * @return The Long object representing the parsed value
	 * @throws Exception
	 *             if {@code str} is null or cannot be parsed as a valid long value
	 * @see #isValidLong(String)
	 * @see #longToString(long)
	 * @see #addLongs(String, String)
	 * @since 10.1
	 */
	public Long toLong(String str) throws Exception {
		try {
			return Long.parseLong(str.trim());
		} catch (NumberFormatException e) {
			lastError = e;
			throw new Exception("Invalid long value: " + str, e);
		}
	}

	/**
	 * Validates whether a string represents a valid long value.
	 * <p>
	 * This method checks if the string can be successfully parsed as a Java long
	 * without throwing an exception. It's useful for validating input before
	 * attempting conversion.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var id = work.getString(&quot;userId&quot;);
	 * if (system.isValidLong(id)) {
	 *     var longId = system.toLong(id);
	 *     // Safe to use
	 * } else {
	 *     task.logmsg(&quot;Invalid user ID: &quot; + id);
	 * }
	 * </pre>
	 * 
	 * @param str
	 *            The string to validate
	 * @return true if the string represents a valid long value, false otherwise
	 * @see #toLong(String)
	 * @since 10.1
	 */
	public boolean isValidLong(String str) {
		if (str == null || str.trim().isEmpty()) {
			return false;
		}
		try {
			Long.parseLong(str.trim());
			return true;
		} catch (NumberFormatException e) {
			lastError = e;
			return false;
		}
	}

	/**
	 * Converts a Java long value to a string for safe JavaScript handling.
	 * <p>
	 * Use this method when returning long values from Java methods to JavaScript
	 * to ensure precision is preserved. The string can then be passed back to
	 * Java using {@link #toLong(String)} without precision loss.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * // Get long from Java object
	 * var timestamp = javaObject.getTimestamp();  // Returns long
	 * var timestampStr = system.longToString(timestamp);
	 * // Now safe to manipulate in JavaScript
	 * work.put(&quot;timestamp&quot;, timestampStr);
	 * </pre>
	 * 
	 * @param value
	 *            The long value to convert
	 * @return String representation of the long value
	 * @see #toLong(String)
	 * @since 10.1
	 */
	public String longToString(long value) {
		return Long.toString(value);
	}

	/**
	 * Compares two long values represented as strings.
	 * <p>
	 * This method allows comparison of long values without converting them to
	 * JavaScript numbers, which would lose precision for large values.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var id1 = &quot;9223372036854775807&quot;;
	 * var id2 = &quot;9223372036854775806&quot;;
	 * var result = system.compareLongs(id1, id2);
	 * if (result &gt; 0) {
	 *     task.logmsg(&quot;id1 is greater&quot;);
	 * } else if (result &lt; 0) {
	 *     task.logmsg(&quot;id2 is greater&quot;);
	 * } else {
	 *     task.logmsg(&quot;Equal&quot;);
	 * }
	 * </pre>
	 * 
	 * @param long1
	 *            First long value as string
	 * @param long2
	 *            Second long value as string
	 * @return -1 if long1 &lt; long2, 0 if equal, 1 if long1 &gt; long2
	 * @throws Exception
	 *             if either {@code long1} or {@code long2} cannot be parsed as a
	 *             valid long value
	 * @see #toLong(String)
	 * @since 10.1
	 */
	public int compareLongs(String long1, String long2) throws Exception {
		try {
			Long l1 = Long.parseLong(long1.trim());
			Long l2 = Long.parseLong(long2.trim());
			return l1.compareTo(l2);
		} catch (NumberFormatException e) {
			lastError = e;
			throw new Exception("Invalid long values for comparison", e);
		}
	}

	/**
	 * Adds two long values represented as strings without precision loss.
	 * <p>
	 * This method performs arithmetic on long values while preserving full
	 * precision. The result is returned as a string to maintain precision when
	 * used in JavaScript.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var baseId = &quot;9000000000000000000&quot;;
	 * var offset = &quot;1000000000000&quot;;
	 * var newId = system.addLongs(baseId, offset);
	 * work.put(&quot;newId&quot;, newId);
	 * </pre>
	 * 
	 * @param long1
	 *            First long value as string
	 * @param long2
	 *            Second long value as string
	 * @return String representation of the sum
	 * @throws Exception
	 *             if either {@code long1} or {@code long2} cannot be parsed as a
	 *             valid long value, or if the result overflows a 64-bit long
	 * @see #subtractLongs(String, String)
	 * @see #multiplyLongs(String, String)
	 * @since 10.1
	 */
	public String addLongs(String long1, String long2) throws Exception {
		try {
			Long l1 = Long.parseLong(long1.trim());
			Long l2 = Long.parseLong(long2.trim());
			return Long.toString(l1 + l2);
		} catch (NumberFormatException e) {
			lastError = e;
			throw new Exception("Invalid long values for addition", e);
		} catch (ArithmeticException e) {
			lastError = e;
			throw new Exception("Long overflow in addition", e);
		}
	}

	/**
	 * Subtracts two long values represented as strings without precision loss.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var total = &quot;9000000000000000000&quot;;
	 * var used = &quot;1000000000000&quot;;
	 * var remaining = system.subtractLongs(total, used);
	 * </pre>
	 * 
	 * @param long1
	 *            First long value as string (minuend)
	 * @param long2
	 *            Second long value as string (subtrahend)
	 * @return String representation of the difference (long1 - long2)
	 * @throws Exception
	 *             if either {@code long1} or {@code long2} cannot be parsed as a
	 *             valid long value, or if the result overflows a 64-bit long
	 * @see #addLongs(String, String)
	 * @since 10.1
	 */
	public String subtractLongs(String long1, String long2) throws Exception {
		try {
			Long l1 = Long.parseLong(long1.trim());
			Long l2 = Long.parseLong(long2.trim());
			return Long.toString(l1 - l2);
		} catch (NumberFormatException e) {
			lastError = e;
			throw new Exception("Invalid long values for subtraction", e);
		} catch (ArithmeticException e) {
			lastError = e;
			throw new Exception("Long overflow in subtraction", e);
		}
	}

	/**
	 * Multiplies two long values represented as strings with overflow detection.
	 * <p>
	 * This method uses Math.multiplyExact() to detect overflow conditions and
	 * throw an exception if the result would exceed the range of a long value.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var quantity = &quot;1000000&quot;;
	 * var price = &quot;5000000&quot;;
	 * var total = system.multiplyLongs(quantity, price);
	 * </pre>
	 * 
	 * @param long1
	 *            First long value as string
	 * @param long2
	 *            Second long value as string
	 * @return String representation of the product
	 * @throws Exception
	 *             if either {@code long1} or {@code long2} cannot be parsed as a
	 *             valid long value, or if the result overflows a 64-bit long
	 * @see #divideLongs(String, String)
	 * @since 10.1
	 */
	public String multiplyLongs(String long1, String long2) throws Exception {
		try {
			Long l1 = Long.parseLong(long1.trim());
			Long l2 = Long.parseLong(long2.trim());
			long result = Math.multiplyExact(l1, l2);
			return Long.toString(result);
		} catch (NumberFormatException e) {
			lastError = e;
			throw new Exception("Invalid long values for multiplication", e);
		} catch (ArithmeticException e) {
			lastError = e;
			throw new Exception("Long overflow in multiplication", e);
		}
	}

	/**
	 * Divides two long values represented as strings.
	 * <p>
	 * This method performs integer division. The result is truncated toward zero.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var total = &quot;1000000000000&quot;;
	 * var count = &quot;1000&quot;;
	 * var average = system.divideLongs(total, count);
	 * </pre>
	 * 
	 * @param long1
	 *            Dividend as string
	 * @param long2
	 *            Divisor as string
	 * @return String representation of the quotient (long1 / long2)
	 * @throws Exception
	 *             if either {@code long1} or {@code long2} cannot be parsed as a
	 *             valid long value, or if {@code long2} is zero
	 * @see #multiplyLongs(String, String)
	 * @since 10.1
	 */
	public String divideLongs(String long1, String long2) throws Exception {
		try {
			Long l1 = Long.parseLong(long1.trim());
			Long l2 = Long.parseLong(long2.trim());
			if (l2 == 0) {
				throw new ArithmeticException("Division by zero");
			}
			return Long.toString(l1 / l2);
		} catch (NumberFormatException e) {
			lastError = e;
			throw new Exception("Invalid long values for division", e);
		} catch (ArithmeticException e) {
			lastError = e;
			throw new Exception("Division error: " + e.getMessage(), e);
		}
	}

	/**
	 * Checks if a long value falls within a specified range.
	 * <p>
	 * This method is useful for validating that long values meet business rules
	 * or constraints without converting to JavaScript numbers.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var userId = &quot;123456789012345&quot;;
	 * var minId = &quot;100000000000000&quot;;
	 * var maxId = &quot;999999999999999&quot;;
	 * if (system.isLongInRange(userId, minId, maxId)) {
	 *     task.logmsg(&quot;User ID is valid&quot;);
	 * }
	 * </pre>
	 * 
	 * @param longStr
	 *            The long value to check as string
	 * @param min
	 *            Minimum value (inclusive) as string
	 * @param max
	 *            Maximum value (inclusive) as string
	 * @return true if min &lt;= longStr &lt;= max, false otherwise
	 * @throws Exception
	 *             if any of {@code longStr}, {@code min}, or {@code max} cannot
	 *             be parsed as a valid long value
	 * @see #compareLongs(String, String)
	 * @since 10.1
	 */
	public boolean isLongInRange(String longStr, String min, String max) throws Exception {
		try {
			Long value = Long.parseLong(longStr.trim());
			Long minVal = Long.parseLong(min.trim());
			Long maxVal = Long.parseLong(max.trim());
			return value >= minVal && value <= maxVal;
		} catch (NumberFormatException e) {
			lastError = e;
			throw new Exception("Invalid long values for range check", e);
		}
	}

	/**
	 * Formats a long value with thousand separators for display purposes.
	 * <p>
	 * This method makes large numbers more readable by inserting separators
	 * (typically commas or periods depending on locale) between groups of digits.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var amount = &quot;1234567890123&quot;;
	 * var formatted = system.formatLongWithSeparator(amount, &quot;,&quot;);
	 * task.logmsg(&quot;Amount: &quot; + formatted);  // &quot;1,234,567,890,123&quot;
	 * </pre>
	 * 
	 * @param longStr
	 *            The long value as string
	 * @param separator
	 *            The separator character to use (e.g., "," or ".")
	 * @return Formatted string with separators
	 * @throws Exception
	 *             if {@code longStr} cannot be parsed as a valid long value
	 * @since 10.1
	 */
	public String formatLongWithSeparator(String longStr, String separator) throws Exception {
		try {
			Long value = Long.parseLong(longStr.trim());
			return String.format("%,d", value).replace(",", separator);
		} catch (NumberFormatException e) {
			lastError = e;
			throw new Exception("Invalid long value for formatting", e);
		}
	}

	/**
	 * Gets the current system time in milliseconds as a string.
	 * <p>
	 * This method returns the current timestamp as a string to avoid precision
	 * loss when handling timestamps in JavaScript. Timestamps in milliseconds
	 * since epoch often exceed JavaScript's safe integer range.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var timestamp = system.getCurrentTimestamp();
	 * work.put(&quot;createdAt&quot;, timestamp);
	 * 
	 * // Later, convert back to Date if needed
	 * var date = system.timestampToDate(timestamp);
	 * </pre>
	 * 
	 * @return Current time in milliseconds since epoch as string
	 * @see #timestampToDate(String)
	 * @see #longToString(long)
	 * @since 10.1
	 */
	public String getCurrentTimestamp() {
		return Long.toString(System.currentTimeMillis());
	}

	// ========================================================================
	// DN and LDAP String Manipulation Methods
	// ========================================================================

	/**
	 * Builds a Distinguished Name (DN) with proper escaping of special characters.
	 * <p>
	 * This method constructs an LDAP DN from components while automatically
	 * escaping special characters according to RFC 4514. Special characters that
	 * are escaped include: , + " \ &lt; &gt; ; =
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * // Before (error-prone)
	 * var dn = &quot;cn=&quot; + firstName + &quot; &quot; + lastName + &quot;,ou=Users,&quot; + baseDN;
	 * 
	 * // After (safe)
	 * var dn = system.buildDN(firstName + &quot; &quot; + lastName, &quot;Users&quot;, baseDN);
	 * // Result: &quot;cn=John Smith,ou=Users,dc=example,dc=com&quot;
	 * </pre>
	 * 
	 * @param cn
	 *            The common name (CN) value. Will be escaped automatically.
	 * @param ou
	 *            The organizational unit (OU) value. Can be null.
	 * @param base
	 *            The base DN (e.g., "dc=example,dc=com"). Can be null.
	 * @return The complete DN string with proper escaping
	 * @see #parseDN(String)
	 * @see #extractCN(String)
	 * @since 10.1
	 */
	public String buildDN(String cn, String ou, String base) {
		StringBuilder dn = new StringBuilder();

		if (cn != null && !cn.isEmpty()) {
			dn.append("cn=").append(escapeDNValue(cn));
		}

		if (ou != null && !ou.isEmpty()) {
			if (dn.length() > 0)
				dn.append(",");
			dn.append("ou=").append(escapeDNValue(ou));
		}

		if (base != null && !base.isEmpty()) {
			if (dn.length() > 0)
				dn.append(",");
			dn.append(base);
		}

		return dn.toString();
	}

	/**
	 * Escapes special characters in a DN value according to RFC 4514.
	 * <p>
	 * This is a helper method used by {@link #buildDN(String, String, String)}.
	 * Special characters that require escaping: , + " \ &lt; &gt; ; =
	 * 
	 * @param value
	 *            The value to escape
	 * @return The escaped value
	 */
	private String escapeDNValue(String value) {
		if (value == null)
			return "";

		StringBuilder escaped = new StringBuilder();
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == ',' || c == '+' || c == '"' || c == '\\' || c == '<' || c == '>' || c == ';' || c == '=') {
				escaped.append('\\');
			}
			escaped.append(c);
		}
		return escaped.toString();
	}

	/**
	 * Parses a Distinguished Name (DN) into its component parts.
	 * <p>
	 * This method breaks down an LDAP DN into a map of attribute types to values.
	 * The map preserves the order of components as they appear in the DN.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var dn = &quot;cn=John Smith,ou=Users,dc=example,dc=com&quot;;
	 * var parts = system.parseDN(dn);
	 * var cn = parts.get(&quot;cn&quot;);  // &quot;John Smith&quot;
	 * var ou = parts.get(&quot;ou&quot;);  // &quot;Users&quot;
	 * var dc = parts.get(&quot;dc&quot;);  // &quot;example&quot; (first occurrence)
	 * </pre>
	 * 
	 * @param dn
	 *            The Distinguished Name to parse
	 * @return Map of attribute types (lowercase) to values with escaped characters
	 *         unescaped
	 * @see #buildDN(String, String, String)
	 * @see #extractCN(String)
	 * @since 10.1
	 */
	public Map<String, String> parseDN(String dn) {
		Map<String, String> components = new LinkedHashMap<>();
		if (dn == null || dn.isEmpty())
			return components;

		try {
			String[] parts = dn.split(",");
			for (String part : parts) {
				String[] kv = part.split("=", 2);
				if (kv.length == 2) {
					String key = kv[0].trim().toLowerCase();
					String value = unescapeDNValue(kv[1].trim());
					components.put(key, value);
				}
			}
		} catch (Exception e) {
			lastError = e;
		}
		return components;
	}

	/**
	 * Unescapes special characters in a DN value.
	 * <p>
	 * This is a helper method used by {@link #parseDN(String)} and
	 * {@link #extractCN(String)}.
	 * 
	 * @param value
	 *            The escaped value
	 * @return The unescaped value
	 */
	private String unescapeDNValue(String value) {
		if (value == null)
			return null;
		return value.replace("\\,", ",").replace("\\+", "+").replace("\\\"", "\"").replace("\\\\", "\\")
				.replace("\\<", "<").replace("\\>", ">").replace("\\;", ";").replace("\\=", "=");
	}

	/**
	 * Extracts the Common Name (CN) from a Distinguished Name.
	 * <p>
	 * This method is a convenience function for extracting just the CN component
	 * from a DN without parsing the entire DN.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var dn = &quot;cn=John Smith,ou=Users,dc=example,dc=com&quot;;
	 * var cn = system.extractCN(dn);  // &quot;John Smith&quot;
	 * </pre>
	 * 
	 * @param dn
	 *            The Distinguished Name
	 * @return The CN value with escaped characters unescaped, or null if no CN is
	 *         found
	 * @see #parseDN(String)
	 * @see #getRDN(String)
	 * @since 10.1
	 */
	public String extractCN(String dn) {
		if (dn == null || dn.isEmpty())
			return null;

		try {
			Pattern pattern = Pattern.compile("cn=([^,]+)",
					Pattern.CASE_INSENSITIVE);
			Matcher matcher = pattern.matcher(dn);
			if (matcher.find()) {
				return unescapeDNValue(matcher.group(1).trim());
			}
		} catch (Exception e) {
			lastError = e;
		}
		return null;
	}

	/**
	 * Gets the Relative Distinguished Name (RDN) from a DN.
	 * <p>
	 * The RDN is the first component of a DN (the leftmost attribute=value pair).
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var dn = &quot;cn=John Smith,ou=Users,dc=example,dc=com&quot;;
	 * var rdn = system.getRDN(dn);  // &quot;cn=John Smith&quot;
	 * </pre>
	 * 
	 * @param dn
	 *            The Distinguished Name
	 * @return The RDN (first component), or the entire DN if no comma is found
	 * @see #getParentDN(String)
	 * @see #extractCN(String)
	 * @since 10.1
	 */
	public String getRDN(String dn) {
		if (dn == null || dn.isEmpty())
			return null;

		try {
			int commaIndex = dn.indexOf(',');
			if (commaIndex > 0) {
				return dn.substring(0, commaIndex).trim();
			}
			return dn.trim();
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Gets the parent DN by removing the RDN.
	 * <p>
	 * This method is useful for navigating up the DN hierarchy.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var dn = &quot;cn=John Smith,ou=Users,dc=example,dc=com&quot;;
	 * var parent = system.getParentDN(dn);  // &quot;ou=Users,dc=example,dc=com&quot;
	 * </pre>
	 * 
	 * @param dn
	 *            The Distinguished Name
	 * @return The parent DN (everything after the first comma), or null if no
	 *         parent exists
	 * @see #getRDN(String)
	 * @since 10.1
	 */
	public String getParentDN(String dn) {
		if (dn == null || dn.isEmpty())
			return null;

		try {
			int commaIndex = dn.indexOf(',');
			if (commaIndex > 0 && commaIndex < dn.length() - 1) {
				return dn.substring(commaIndex + 1).trim();
			}
		} catch (Exception e) {
			lastError = e;
		}
		return null;
	}

	/**
	 * Normalizes an email address to lowercase and removes leading/trailing dots.
	 * <p>
	 * This method performs standard email normalization: converts to lowercase,
	 * trims whitespace, and removes leading/trailing dots. It also validates that
	 * the result matches a basic email pattern.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var email = &quot;  John.Smith@EXAMPLE.COM  &quot;;
	 * var normalized = system.normalizeEmail(email);  // &quot;john.smith@example.com&quot;
	 * </pre>
	 * 
	 * @param email
	 *            The email address to normalize
	 * @return The normalized email address, or null if the email is invalid
	 * @since 10.1
	 */
	public String normalizeEmail(String email) {
		if (email == null || email.isEmpty())
			return null;

		try {
			String normalized = email.trim().toLowerCase();
			// Remove leading/trailing dots
			normalized = normalized.replaceAll("^\\.+|\\.+$", "");
			// Validate basic format
			if (normalized.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
				return normalized;
			}
		} catch (Exception e) {
			lastError = e;
		}
		return null;
	}

	// ========================================================================
	// Error Handling and Retry Logic Methods
	// ========================================================================

	/**
	 * Retries an operation with exponential backoff.
	 * <p>
	 * This method is essential for reliable connector operations in production
	 * environments. It automatically retries failed operations with increasing
	 * delays between attempts, which is particularly useful for handling transient
	 * network errors, temporary service unavailability, or rate limiting.
	 * <p>
	 * The delay between retries doubles with each attempt (exponential backoff),
	 * starting with the specified initial delay.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var result = system.retryWithBackoff(
	 *     new java.util.concurrent.Callable({
	 *         call: function() {
	 *             return connector.lookup(dn);
	 *         }
	 *     }),
	 *     3,    // max attempts
	 *     1000  // initial delay 1 second
	 * );
	 * </pre>
	 * @param <T>
	 *            The type of result returned by the operation
	 * 
	 * @param operation
	 *            The operation to retry, as a Callable that returns a result
	 * @param maxAttempts
	 *            Maximum number of attempts (must be &gt; 0)
	 * @param initialDelayMs
	 *            Initial delay in milliseconds before first retry
	 * @return The result of the operation if successful, or null if all attempts
	 *         fail
	 * @see #isTransientError(Exception)
	 * @since 10.1
	 */
	public <T> T retryWithBackoff(java.util.concurrent.Callable<T> operation, int maxAttempts, int initialDelayMs) {
		int attempt = 0;
		int delay = initialDelayMs;

		while (attempt < maxAttempts) {
			try {
				return operation.call();
			} catch (Exception e) {
				attempt++;
				lastError = e;

				if (attempt >= maxAttempts) {
					getServer().getLog().error("Max retry attempts reached: " + e.getMessage());
					return null;
				}

				getServer().getLog()
						.warn("Attempt " + attempt + " failed, retrying in " + delay + "ms: " + e.getMessage());

				try {
					Thread.sleep(delay);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					return null;
				}

				delay *= 2; // Exponential backoff
			}
		}
		return null;
	}

	/**
	 * Determines if an exception represents a transient error that can be retried.
	 * <p>
	 * This method examines the exception message to identify common patterns of
	 * transient errors such as timeouts, connection failures, and rate limiting.
	 * Use this in conjunction with {@link #retryWithBackoff} to implement
	 * intelligent retry logic.
	 * <p>
	 * <b>Transient error patterns detected:</b>
	 * <ul>
	 * <li>timeout</li>
	 * <li>connection refused</li>
	 * <li>connection reset</li>
	 * <li>temporarily unavailable</li>
	 * <li>service unavailable</li>
	 * <li>too many requests</li>
	 * <li>rate limit</li>
	 * </ul>
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * try {
	 *     connector.lookup(dn);
	 * } catch (e) {
	 *     if (system.isTransientError(e)) {
	 *         // Retry the operation
	 *         system.retryEntry();
	 *     } else {
	 *         // Fatal error, skip entry
	 *         system.skipEntry(&quot;Fatal error: &quot; + e.getMessage());
	 *     }
	 * }
	 * </pre>
	 * 
	 * @param e
	 *            The exception to examine
	 * @return true if the error appears to be transient and retryable, false
	 *         otherwise
	 * @see #retryWithBackoff(java.util.concurrent.Callable, int, int)
	 * @since 10.1
	 */
	public boolean isTransientError(Exception e) {
		if (e == null)
			return false;

		String msg = e.getMessage();
		if (msg == null)
			return false;

		msg = msg.toLowerCase();

		// Common transient error patterns
		return msg.contains("timeout") || msg.contains("connection refused") || msg.contains("connection reset")
				|| msg.contains("temporarily unavailable") || msg.contains("service unavailable")
				|| msg.contains("too many requests") || msg.contains("rate limit");
	}
	/**
	 * Converts a timestamp string (milliseconds since epoch) to a Date object.
	 * <p>
	 * This method is the complement to {@link #getCurrentTimestamp()}. It converts
	 * a timestamp string back to a Java Date object for use in date operations.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var timestampStr = work.getString(&quot;createdAt&quot;);
	 * var date = system.timestampToDate(timestampStr);
	 * var formatted = system.formatDate(date, &quot;yyyy-MM-dd HH:mm:ss&quot;);
	 * </pre>
	 * 
	 * @param timestamp
	 *            The timestamp as string (milliseconds since epoch)
	 * @return Date object representing the timestamp
	 * @throws Exception
	 *             if {@code timestamp} cannot be parsed as a valid long value
	 * @see #getCurrentTimestamp()
	 * @see #toLong(String)
	 * @since 10.1
	 */
	public Date timestampToDate(String timestamp) throws Exception {
		try {
			long millis = Long.parseLong(timestamp.trim());
			return new Date(millis);
		} catch (NumberFormatException e) {
			lastError = e;
			throw new Exception("Invalid timestamp: " + timestamp, e);
		}
	}

	/**
	 * Generates a user ID from first and last name.
	 * <p>
	 * This method creates a simple user ID by taking the first letter of the first
	 * name and appending the last name, converting to lowercase and removing
	 * non-alphanumeric characters. This is a common pattern for generating login
	 * IDs.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var uid = system.generateUid(&quot;John&quot;, &quot;Smith&quot;);  // &quot;jsmith&quot;
	 * var uid2 = system.generateUid(&quot;Mary&quot;, &quot;O'Brien&quot;);  // &quot;mobrien&quot;
	 * </pre>
	 * 
	 * @param firstName
	 *            The first name
	 * @param lastName
	 *            The last name
	 * @return Generated user ID (lowercase, alphanumeric only), or null if inputs
	 *         are invalid
	 * @since 10.1
	 */
	public String generateUid(String firstName, String lastName) {
		if (firstName == null || lastName == null)
			return null;

		try {
			String uid = (firstName.substring(0, 1) + lastName).toLowerCase();
			// Remove non-alphanumeric characters
			uid = uid.replaceAll("[^a-z0-9]", "");
			return uid;
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	// ========================================================================
	// Phase 2: TDI-Specific Utility Methods
	// ========================================================================
	// Defensive null handling, iterator utilities, configuration helpers,
	// batch processing, performance measurement, and LDAP filter construction
	// ========================================================================

	/**
	 * Returns the first non-null value from the provided arguments.
	 * <p>
	 * This method implements the SQL COALESCE function pattern, returning the
	 * first non-null value from a list of candidates. It's particularly useful
	 * for providing default values when attributes may be missing or null.
	 * <p>
	 * <b>Background:</b> In IBM TDI scripts, attributes may not exist or may be
	 * null. This method provides a clean way to handle these cases without
	 * verbose null checks.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * // Use email, or fallback to username@domain if email is null
	 * var email = system.coalesce(work.getString("mail"), 
	 *                             work.getString("uid") + "@example.com");
	 * 
	 * // Chain multiple fallbacks
	 * var displayName = system.coalesce(
	 *     work.getString("displayName"),
	 *     work.getString("cn"),
	 *     work.getString("uid"),
	 *     "Unknown User"
	 * );
	 * </pre>
	 * 
	 * @param value
	 *            The primary value to check
	 * @param defaultValue
	 *            The fallback value to use if value is null
	 * @return value if not null, otherwise defaultValue
	 * @see #isEmpty(Object)
	 * @since 10.1
	 */
	public Object coalesce(Object value, Object defaultValue) {
		return value != null ? value : defaultValue;
	}

	/**
	 * Checks if a value is empty (null, empty string, empty collection, etc.).
	 * <p>
	 * This method provides a comprehensive emptiness check that works with
	 * multiple data types: null values, strings, collections, maps, and arrays.
	 * It's more convenient than checking each type separately.
	 * <p>
	 * <b>Emptiness Rules:</b>
	 * <ul>
	 * <li>null → true</li>
	 * <li>Empty string or whitespace-only string → true</li>
	 * <li>Empty Collection → true</li>
	 * <li>Empty Map → true</li>
	 * <li>Empty array → true</li>
	 * <li>All other values → false</li>
	 * </ul>
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * if (system.isEmpty(work.getString("mail"))) {
	 *     task.logmsg("Email is missing or empty");
	 * }
	 * 
	 * // Check if multi-valued attribute has values
	 * if (!system.isEmpty(work.getObject("memberOf"))) {
	 *     var groups = work.getObject("memberOf");
	 *     // Process groups
	 * }
	 * </pre>
	 * 
	 * @param value
	 *            The value to check
	 * @return true if the value is considered empty, false otherwise
	 * @see #coalesce(Object, Object)
	 * @since 10.1
	 */
	public boolean isEmpty(Object value) {
		if (value == null)
			return true;
		if (value instanceof String)
			return ((String) value).trim().isEmpty();
		if (value instanceof Collection)
			return ((Collection<?>) value).isEmpty();
		if (value instanceof Map)
			return ((Map<?, ?>) value).isEmpty();
		if (value.getClass().isArray())
			return Array.getLength(value) == 0;
		return false;
	}

	/**
	 * Returns the first Entry from an Iterator without consuming the entire
	 * iterator.
	 * <p>
	 * This method is useful when you only need to check if a search returned any
	 * results or when you want to process just the first result. It's more
	 * efficient than converting the entire iterator to a list when you only need
	 * one entry.
	 * <p>
	 * <b>Background:</b> IBM TDI connector searches return Iterator&lt;Entry&gt;.
	 * Often you only need to check if results exist or get the first result. This
	 * method provides a safe way to do that without null pointer exceptions.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * // Check if user exists
	 * var iterator = conn.search("(uid=" + userId + ")");
	 * var entry = system.firstEntry(iterator);
	 * if (entry != null) {
	 *     task.logmsg("User found: " + entry.getDN());
	 * } else {
	 *     task.logmsg("User not found");
	 * }
	 * </pre>
	 * 
	 * @param iterator
	 *            The iterator to get the first entry from
	 * @return The first Entry, or null if iterator is null or empty
	 * @see #toList(Iterator)
	 * @since 10.1
	 */
	public Entry firstEntry(Iterator<Entry> iterator) {
		if (iterator == null)
			return null;
		try {
			if (iterator.hasNext()) {
				return iterator.next();
			}
		} catch (Exception e) {
			lastError = e;
		}
		return null;
	}

	/**
	 * Converts an Iterator of Entries to a List.
	 * <p>
	 * This method consumes the entire iterator and returns all entries as a List.
	 * This is useful when you need to process entries multiple times, sort them,
	 * or use List-specific operations.
	 * <p>
	 * <b>Warning:</b> This method loads all entries into memory. For large result
	 * sets, consider processing the iterator directly instead of converting to a
	 * list.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * // Get all users and count them
	 * var iterator = conn.search("(objectClass=person)");
	 * var entries = system.toList(iterator);
	 * task.logmsg("Found " + entries.size() + " users");
	 * 
	 * // Process entries multiple times
	 * for (var i = 0; i &lt; entries.size(); i++) {
	 *     var entry = entries.get(i);
	 *     // First pass processing
	 * }
	 * for (var i = 0; i &lt; entries.size(); i++) {
	 *     var entry = entries.get(i);
	 *     // Second pass processing
	 * }
	 * </pre>
	 * 
	 * @param iterator
	 *            The iterator to convert
	 * @return List of all entries from the iterator (empty list if iterator is
	 *         null)
	 * @see #firstEntry(Iterator)
	 * @since 10.1
	 */
	public List<Entry> toList(Iterator<Entry> iterator) {
		List<Entry> result = new ArrayList<>();
		if (iterator == null)
			return result;

		try {
			while (iterator.hasNext()) {
				Entry entry = iterator.next();
				if (entry != null) {
					result.add(entry);
				}
			}
		} catch (Exception e) {
			lastError = e;
		}
		return result;
	}

	/**
	 * Gets an environment variable value.
	 * <p>
	 * This method provides access to system environment variables, which is useful
	 * for configuration that varies between environments (dev, test, prod) without
	 * modifying TDI properties files.
	 * <p>
	 * <b>Use Cases:</b>
	 * <ul>
	 * <li>Database connection strings</li>
	 * <li>API endpoints that differ per environment</li>
	 * <li>Credentials stored in environment variables</li>
	 * <li>Feature flags</li>
	 * </ul>
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * // Get database host from environment
	 * var dbHost = system.getEnv("DATABASE_HOST");
	 * if (dbHost == null) {
	 *     dbHost = "localhost"; // fallback
	 * }
	 * 
	 * // Use with coalesce for cleaner code
	 * var apiUrl = system.coalesce(
	 *     system.getEnv("API_URL"),
	 *     "https://api.example.com"
	 * );
	 * </pre>
	 * 
	 * @param name
	 *            The environment variable name
	 * @return The environment variable value, or null if not set or on error
	 * @see #getTDIProperty(String)
	 * @see #coalesce(Object, Object)
	 * @since 10.1
	 */
	public String getEnv(String name) {
		if (name == null || name.isEmpty())
			return null;

		try {
			return System.getenv(name);
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Gets a required TDI property, throwing an exception if not set.
	 * <p>
	 * This method is useful for validating that critical configuration is present
	 * before starting an AssemblyLine. It fails fast with a clear error message
	 * rather than allowing the AL to run with missing configuration.
	 * <p>
	 * <b>Background:</b> Many TDI scripts require certain properties to be set.
	 * Rather than checking for null throughout the script, this method validates
	 * required properties at startup.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * // Validate required properties at AL startup
	 * try {
	 *     var ldapHost = system.requireConfig("ldap.host");
	 *     var ldapPort = system.requireConfig("ldap.port");
	 *     var baseDN = system.requireConfig("ldap.baseDN");
	 *     
	 *     // All required config present, continue
	 *     task.logmsg("Configuration validated");
	 * } catch (e) {
	 *     task.logmsg("Configuration error: " + e.getMessage());
	 *     system.abortAL("Missing required configuration");
	 * }
	 * </pre>
	 * 
	 * @param key
	 *            The TDI property key
	 * @return The property value (never null)
	 * @throws Exception
	 *             if the property identified by {@code key} is not set or is empty
	 * @see #getTDIProperty(String)
	 * @since 10.1
	 */
	public String requireConfig(String key) throws Exception {
		String value = (String) getTDIProperty(key);
		if (value == null || value.isEmpty()) {
			throw new Exception("Required configuration missing: " + key);
		}
		return value;
	}

	/**
	 * Processes a list in batches using a callback function.
	 * <p>
	 * This method is essential for handling large datasets efficiently. Instead of
	 * processing all items at once (which can cause memory issues or overwhelm
	 * target systems), it processes items in manageable batches.
	 * <p>
	 * <b>Use Cases:</b>
	 * <ul>
	 * <li>Bulk updates to target systems with rate limits</li>
	 * <li>Processing large LDAP search results</li>
	 * <li>Batch database operations</li>
	 * <li>Memory-efficient processing of large datasets</li>
	 * </ul>
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * // Process 1000 users in batches of 100
	 * var users = system.toList(conn.search("(objectClass=person)"));
	 * 
	 * system.processInBatches(users, 100, new system.BatchProcessor({
	 *     process: function(batch) {
	 *         task.logmsg("Processing batch of " + batch.size() + " users");
	 *         
	 *         for (var i = 0; i &lt; batch.size(); i++) {
	 *             var entry = batch.get(i);
	 *             // Process each entry in batch
	 *             targetConn.update(entry);
	 *         }
	 *         
	 *         task.logmsg("Batch complete");
	 *     }
	 * }));
	 * </pre>
	 * @param <T>
	 *            The type of elements in the list
	 * 
	 * @param list
	 *            The list to process
	 * @param batchSize
	 *            Number of items per batch (must be &gt; 0)
	 * @param processor
	 *            The BatchProcessor callback to handle each batch
	 * @see BatchProcessor
	 * @since 10.1
	 */
	public <T> void processInBatches(List<T> list, int batchSize, BatchProcessor<T> processor) {
		if (list == null || processor == null || batchSize <= 0)
			return;

		try {
			List<T> batch = new ArrayList<T>();
			for (int i = 0; i < list.size(); i++) {
				batch.add(list.get(i));

				if (batch.size() >= batchSize || i == list.size() - 1) {
					processor.process(batch);
					batch.clear();
				}
			}
		} catch (Exception e) {
			lastError = e;
		}
	}

	/**
	 * Interface for batch processing callbacks.
	 * <p>
	 * Implement this interface to define how each batch should be processed.
	 *
	 * @param <T> The type of elements in the batch
	 * @see #processInBatches(List, int, BatchProcessor)
	 * @since 10.1
	 */
	public interface BatchProcessor<T> {
		/**
		 * Process a batch of items.
		 *
		 * @param batch
		 *            The batch to process
		 * @throws Exception
		 *             if batch processing fails for any item in the batch
		 */
		void process(List<T> batch) throws Exception;
	}

	/**
	 * Measures and logs the execution time of an operation.
	 * <p>
	 * This method is useful for performance analysis and optimization. It executes
	 * an operation, measures how long it takes, logs the duration, and returns the
	 * result.
	 * <p>
	 * <b>Use Cases:</b>
	 * <ul>
	 * <li>Identifying slow operations in AssemblyLines</li>
	 * <li>Performance testing different approaches</li>
	 * <li>Monitoring production performance</li>
	 * <li>Debugging timeout issues</li>
	 * </ul>
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * // Measure LDAP search performance
	 * var results = system.measureTime(
	 *     new java.util.concurrent.Callable({
	 *         call: function() {
	 *             return conn.search("(objectClass=person)");
	 *         }
	 *     }),
	 *     "LDAP Search"
	 * );
	 * // Log output: "LDAP Search took 1234ms"
	 * 
	 * // Measure complex operation
	 * system.measureTime(
	 *     new java.util.concurrent.Callable({
	 *         call: function() {
	 *             // Complex processing
	 *             for (var i = 0; i &lt; 1000; i++) {
	 *                 // Do work
	 *             }
	 *             return null;
	 *         }
	 *     }),
	 *     "Complex Processing"
	 * );
	 * </pre>
	 *
	 * @param <T>
	 *            The type of result returned by the operation
	 * @param operation
	 *            The operation to measure (as a Callable)
	 * @param label
	 *            A descriptive label for the log message
	 * @return The result of the operation, or null if it throws an exception
	 * @since 10.1
	 */
	public <T> T measureTime(java.util.concurrent.Callable<T> operation, String label) {
		long startTime = System.currentTimeMillis();
		T result = null;

		try {
			result = operation.call();
		} catch (Exception e) {
			lastError = e;
		} finally {
			long elapsed = System.currentTimeMillis() - startTime;
			getServer().getLog().info(label + " took " + elapsed + "ms");
		}

		return result;
	}

	/**
	 * Builds an LDAP filter with proper value escaping.
	 * <p>
	 * This method constructs LDAP search filters while automatically escaping
	 * special characters in values. This prevents LDAP injection attacks and
	 * ensures filters work correctly with values containing special characters.
	 * <p>
	 * <b>Supported Operators:</b>
	 * <ul>
	 * <li>"equals" or "=" → (attr=value)</li>
	 * <li>"contains" or "~=" → (attr=*value*)</li>
	 * <li>"startswith" → (attr=value*)</li>
	 * <li>"endswith" → (attr=*value)</li>
	 * <li>"present" → (attr=*)</li>
	 * </ul>
	 * <p>
	 * <b>Special Characters Escaped:</b> \ * ( ) \0
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * // Simple equality filter
	 * var filter = system.buildLDAPFilter("uid", "equals", "jsmith");
	 * // Result: "(uid=jsmith)"
	 * 
	 * // Contains filter
	 * var filter = system.buildLDAPFilter("cn", "contains", "John");
	 * // Result: "(cn=*John*)"
	 * 
	 * // Handles special characters safely
	 * var filter = system.buildLDAPFilter("cn", "equals", "Smith (Admin)");
	 * // Result: "(cn=Smith \\28Admin\\29)" - parentheses escaped
	 * 
	 * // Build complex filter
	 * var f1 = system.buildLDAPFilter("givenName", "equals", firstName);
	 * var f2 = system.buildLDAPFilter("sn", "equals", lastName);
	 * var complexFilter = "(&amp;" + f1 + f2 + ")";
	 * </pre>
	 * 
	 * @param attribute
	 *            The LDAP attribute name
	 * @param operator
	 *            The comparison operator (equals, contains, startswith, endswith,
	 *            present)
	 * @param value
	 *            The value to search for (will be escaped automatically)
	 * @return The LDAP filter string, or null if parameters are invalid
	 * @since 10.1
	 */
	public String buildLDAPFilter(String attribute, String operator, String value) {
		if (attribute == null || operator == null || value == null)
			return null;

		try {
			String escapedValue = escapeLDAPFilterValue(value);

			switch (operator.toLowerCase()) {
			case "equals":
			case "=":
				return "(" + attribute + "=" + escapedValue + ")";
			case "contains":
			case "~=":
				return "(" + attribute + "=*" + escapedValue + "*)";
			case "startswith":
				return "(" + attribute + "=" + escapedValue + "*)";
			case "endswith":
				return "(" + attribute + "=*" + escapedValue + ")";
			case "present":
				return "(" + attribute + "=*)";
			default:
				return "(" + attribute + operator + escapedValue + ")";
			}
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Escapes special characters in LDAP filter values according to RFC 4515.
	 * <p>
	 * This is a helper method used by {@link #buildLDAPFilter}. Special characters
	 * that require escaping: \ * ( ) \0
	 * 
	 * @param value
	 *            The value to escape
	 * @return The escaped value
	 */
	private String escapeLDAPFilterValue(String value) {
		if (value == null)
			return "";

		return value.replace("\\", "\\5c").replace("*", "\\2a").replace("(", "\\28").replace(")", "\\29")
				.replace("\0", "\\00");
	}

	// ========================================================================
	// Phase 3: Filter Conversion Methods
	// ========================================================================
	// Convert between LDAP filters and other query formats (SQL, SCIM, REST,
	// GraphQL). Includes filter validation, optimization, and builder utilities.
	// ========================================================================

	/**
	 * Converts an LDAP filter to a SQL WHERE clause.
	 * <p>
	 * This method enables connector switching between LDAP and JDBC sources by
	 * translating LDAP filter syntax to SQL WHERE clause syntax. It handles
	 * logical operators (AND, OR, NOT) and common comparison operators.
	 * <p>
	 * <b>Background:</b> When migrating from LDAP to database backends or
	 * implementing hybrid identity stores, you need to translate search criteria
	 * between formats. This method automates that translation.
	 * <p>
	 * <b>Supported Conversions:</b>
	 * <ul>
	 * <li>(&...) -> AND</li>
	 * <li>(|...) -> OR</li>
	 * <li>(!...) -> NOT</li>
	 * <li>(attr=value) -> attr = 'value'</li>
	 * <li>(attr=*value*) -> attr LIKE '%value%'</li>
	 * <li>(attr=value*) -> attr LIKE 'value%'</li>
	 * <li>(attr=*value) -> attr LIKE '%value'</li>
	 * <li>(attr=*) → attr IS NOT NULL</li>
	 * </ul>
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * // Convert LDAP filter to SQL
	 * var ldapFilter = "(&amp;(objectClass=person)(mail=*@example.com))";
	 * var sqlWhere = system.ldapFilterToSQL(ldapFilter);
	 * // Result: "(objectClass = 'person' AND mail LIKE '%@example.com')"
	 * 
	 * // Use in JDBC query
	 * var sql = "SELECT * FROM users WHERE " + sqlWhere;
	 * </pre>
	 * 
	 * @param ldapFilter
	 *            The LDAP filter to convert
	 * @return SQL WHERE clause, or null if conversion fails
	 * @see #sqlWhereToLDAPFilter(String)
	 * @see #validateLDAPFilter(String)
	 * @since 10.1
	 */
	public String ldapFilterToSQL(String ldapFilter) {
		if (ldapFilter == null || ldapFilter.isEmpty())
			return null;

		try {
			return parseLDAPFilterToSQL(ldapFilter);
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Helper method to recursively parse LDAP filter to SQL.
	 */
	private String parseLDAPFilterToSQL(String filter) {
		filter = filter.trim();
		if (filter.startsWith("(") && filter.endsWith(")")) {
			filter = filter.substring(1, filter.length() - 1);
		}

		// Handle logical operators
		if (filter.startsWith("&")) {
			return parseLogicalOperatorToSQL(filter.substring(1), "AND");
		} else if (filter.startsWith("|")) {
			return parseLogicalOperatorToSQL(filter.substring(1), "OR");
		} else if (filter.startsWith("!")) {
			return "NOT (" + parseLDAPFilterToSQL(filter.substring(1)) + ")";
		}

		// Handle simple filter
		return parseSimpleFilterToSQL(filter);
	}

	/**
	 * Helper method to parse simple LDAP filter to SQL.
	 */
	private String parseSimpleFilterToSQL(String filter) {
		if (filter.contains("=")) {
			String[] parts = filter.split("=", 2);
			String attr = parts[0].trim();
			String value = parts[1].trim();

			if (value.equals("*")) {
				return attr + " IS NOT NULL";
			} else if (value.startsWith("*") && value.endsWith("*")) {
				return attr + " LIKE '%" + value.substring(1, value.length() - 1).replace("'", "''") + "%'";
			} else if (value.startsWith("*")) {
				return attr + " LIKE '%" + value.substring(1).replace("'", "''") + "'";
			} else if (value.endsWith("*")) {
				return attr + " LIKE '" + value.substring(0, value.length() - 1).replace("'", "''") + "%'";
			} else {
				return attr + " = '" + value.replace("'", "''") + "'";
			}
		}

		return filter;
	}

	/**
	 * Helper method to parse logical operators in LDAP filter to SQL.
	 */
	private String parseLogicalOperatorToSQL(String filter, String operator) {
		List<String> conditions = new ArrayList<>();
		int depth = 0;
		StringBuilder current = new StringBuilder();

		for (char c : filter.toCharArray()) {
			if (c == '(')
				depth++;
			else if (c == ')')
				depth--;

			current.append(c);

			if (depth == 0 && current.length() > 0) {
				conditions.add(parseLDAPFilterToSQL(current.toString()));
				current = new StringBuilder();
			}
		}

		return "(" + String.join(" " + operator + " ", conditions) + ")";
	}

	/**
	 * Converts a SQL WHERE clause to an LDAP filter.
	 * <p>
	 * This method is the reverse of {@link #ldapFilterToSQL}, enabling translation
	 * from SQL syntax back to LDAP filter syntax. Useful when building LDAP queries
	 * from SQL-based search criteria.
	 * <p>
	 * <b>Supported Conversions:</b>
	 * <ul>
	 * <li>AND → (&amp;...)</li>
	 * <li>OR → (|...)</li>
	 * <li>NOT → (!...)</li>
	 * <li>attr = 'value' → (attr=value)</li>
	 * <li>attr LIKE '%value%' → (attr=*value*)</li>
	 * <li>attr LIKE 'value%' → (attr=value*)</li>
	 * <li>attr LIKE '%value' → (attr=*value)</li>
	 * <li>attr IS NOT NULL → (attr=*)</li>
	 * </ul>
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var sqlWhere = "objectClass = 'person' AND mail LIKE '%@example.com'";
	 * var ldapFilter = system.sqlWhereToLDAPFilter(sqlWhere);
	 * // Result: "(&amp;(objectClass=person)(mail=*@example.com))"
	 * </pre>
	 * 
	 * @param sqlWhere
	 *            The SQL WHERE clause to convert
	 * @return LDAP filter, or null if conversion fails
	 * @see #ldapFilterToSQL(String)
	 * @since 10.1
	 */
	public String sqlWhereToLDAPFilter(String sqlWhere) {
		if (sqlWhere == null || sqlWhere.isEmpty())
			return null;

		try {
			return parseSQLToLDAPFilter(sqlWhere);
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Helper method to parse SQL WHERE clause to LDAP filter.
	 */
	private String parseSQLToLDAPFilter(String sql) {
		sql = sql.trim();

		// Handle AND/OR operators
		if (sql.toUpperCase().contains(" AND ")) {
			String[] parts = sql.split("(?i)\\s+AND\\s+");
			StringBuilder ldap = new StringBuilder("(&");
			for (String part : parts) {
				ldap.append(parseSQLToLDAPFilter(part.trim()));
			}
			ldap.append(")");
			return ldap.toString();
		} else if (sql.toUpperCase().contains(" OR ")) {
			String[] parts = sql.split("(?i)\\s+OR\\s+");
			StringBuilder ldap = new StringBuilder("(|");
			for (String part : parts) {
				ldap.append(parseSQLToLDAPFilter(part.trim()));
			}
			ldap.append(")");
			return ldap.toString();
		}

		// Handle simple conditions
		if (sql.contains("=")) {
			String[] parts = sql.split("=", 2);
			String attr = parts[0].trim();
			String value = parts[1].trim().replace("'", "");
			return "(" + attr + "=" + value + ")";
		} else if (sql.toUpperCase().contains(" LIKE ")) {
			String[] parts = sql.split("(?i)\\s+LIKE\\s+", 2);
			String attr = parts[0].trim();
			String value = parts[1].trim().replace("'", "").replace("%", "*");
			return "(" + attr + "=" + value + ")";
		} else if (sql.toUpperCase().contains(" IS NOT NULL")) {
			String attr = sql.split("(?i)\\s+IS\\s+NOT\\s+NULL")[0].trim();
			return "(" + attr + "=*)";
		}

		return sql;
	}

	/**
	 * Converts a SCIM filter to an LDAP filter.
	 * <p>
	 * SCIM (System for Cross-domain Identity Management) is a modern standard for
	 * identity provisioning. This method translates SCIM filter syntax to LDAP
	 * filter syntax, enabling integration with SCIM-based identity systems.
	 * <p>
	 * <b>Supported SCIM Operators:</b>
	 * <ul>
	 * <li>eq (equals) → =</li>
	 * <li>ne (not equals) → !(=)</li>
	 * <li>co (contains) → =*value*</li>
	 * <li>sw (starts with) → =value*</li>
	 * <li>ew (ends with) → =*value</li>
	 * <li>pr (present) → =*</li>
	 * <li>gt/ge (greater than/equal) → &gt;=</li>
	 * <li>lt/le (less than/equal) → &lt;=</li>
	 * </ul>
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var scimFilter = 'userName eq "bjensen" and emails.value co "@example.com"';
	 * var ldapFilter = system.scimFilterToLDAP(scimFilter);
	 * // Result: "(&amp;(userName=bjensen)(emails.value=*@example.com*))"
	 * </pre>
	 * 
	 * @param scimFilter
	 *            The SCIM filter to convert
	 * @return LDAP filter, or null if conversion fails
	 * @see #ldapFilterToSCIM(String)
	 * @since 10.1
	 */
	public String scimFilterToLDAP(String scimFilter) {
		if (scimFilter == null || scimFilter.isEmpty())
			return null;

		try {
			return parseSCIMToLDAP(scimFilter);
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Helper method to parse SCIM filter to LDAP.
	 */
	private String parseSCIMToLDAP(String scim) {
		scim = scim.trim();

		// Handle logical operators
		if (scim.toLowerCase().contains(" and ")) {
			String[] parts = scim.split("(?i)\\s+and\\s+");
			StringBuilder ldap = new StringBuilder("(&");
			for (String part : parts) {
				ldap.append(parseSCIMToLDAP(part.trim()));
			}
			ldap.append(")");
			return ldap.toString();
		} else if (scim.toLowerCase().contains(" or ")) {
			String[] parts = scim.split("(?i)\\s+or\\s+");
			StringBuilder ldap = new StringBuilder("(|");
			for (String part : parts) {
				ldap.append(parseSCIMToLDAP(part.trim()));
			}
			ldap.append(")");
			return ldap.toString();
		}

		// Handle SCIM operators
		String[] operators = { "eq", "ne", "co", "sw", "ew", "pr", "gt", "ge", "lt", "le" };

		for (String op : operators) {
			if (scim.toLowerCase().contains(" " + op + " ")) {
				String[] parts = scim.split("(?i)\\s+" + op + "\\s+", 2);
				String attr = parts[0].trim();
				String value = parts.length > 1 ? parts[1].trim().replace("\"", "") : "";

				switch (op.toLowerCase()) {
				case "eq":
					return "(" + attr + "=" + value + ")";
				case "ne":
					return "(!(" + attr + "=" + value + "))";
				case "co":
					return "(" + attr + "=*" + value + "*)";
				case "sw":
					return "(" + attr + "=" + value + "*)";
				case "ew":
					return "(" + attr + "=*" + value + ")";
				case "pr":
					return "(" + attr + "=*)";
				case "gt":
				case "ge":
					return "(" + attr + ">=" + value + ")";
				case "lt":
				case "le":
					return "(" + attr + "<=" + value + ")";
				}
			}
		}

		return scim;
	}

	/**
	 * Converts an LDAP filter to a SCIM filter.
	 * <p>
	 * This method is the reverse of {@link #scimFilterToLDAP}, translating LDAP
	 * filter syntax to SCIM filter syntax for integration with SCIM-based systems.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var ldapFilter = "(&amp;(userName=bjensen)(mail=*@example.com*))";
	 * var scimFilter = system.ldapFilterToSCIM(ldapFilter);
	 * // Result: 'userName eq "bjensen" and mail co "@example.com"'
	 * </pre>
	 * 
	 * @param ldapFilter
	 *            The LDAP filter to convert
	 * @return SCIM filter, or null if conversion fails
	 * @see #scimFilterToLDAP(String)
	 * @since 10.1
	 */
	public String ldapFilterToSCIM(String ldapFilter) {
		if (ldapFilter == null || ldapFilter.isEmpty())
			return null;

		try {
			return parseLDAPToSCIM(ldapFilter);
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Helper method to parse LDAP filter to SCIM.
	 */
	private String parseLDAPToSCIM(String filter) {
		filter = filter.trim();
		if (filter.startsWith("(") && filter.endsWith(")")) {
			filter = filter.substring(1, filter.length() - 1);
		}

		// Handle logical operators
		if (filter.startsWith("&")) {
			return parseLogicalOperatorToSCIM(filter.substring(1), "and");
		} else if (filter.startsWith("|")) {
			return parseLogicalOperatorToSCIM(filter.substring(1), "or");
		} else if (filter.startsWith("!")) {
			return "not (" + parseLDAPToSCIM(filter.substring(1)) + ")";
		}

		// Handle simple filter
		if (filter.contains("=")) {
			String[] parts = filter.split("=", 2);
			String attr = parts[0].trim();
			String value = parts[1].trim();

			if (value.equals("*")) {
				return attr + " pr";
			} else if (value.startsWith("*") && value.endsWith("*")) {
				return attr + " co \"" + value.substring(1, value.length() - 1) + "\"";
			} else if (value.startsWith("*")) {
				return attr + " ew \"" + value.substring(1) + "\"";
			} else if (value.endsWith("*")) {
				return attr + " sw \"" + value.substring(0, value.length() - 1) + "\"";
			} else {
				return attr + " eq \"" + value + "\"";
			}
		}

		return filter;
	}

	/**
	 * Helper method to parse logical operators in LDAP filter to SCIM.
	 */
	private String parseLogicalOperatorToSCIM(String filter, String operator) {
		List<String> conditions = new ArrayList<>();
		int depth = 0;
		StringBuilder current = new StringBuilder();

		for (char c : filter.toCharArray()) {
			if (c == '(')
				depth++;
			else if (c == ')')
				depth--;

			current.append(c);

			if (depth == 0 && current.length() > 0) {
				conditions.add(parseLDAPToSCIM(current.toString()));
				current = new StringBuilder();
			}
		}

		return String.join(" " + operator + " ", conditions);
	}

	/**
	 * Converts an LDAP filter to REST API query parameters.
	 * <p>
	 * This method extracts attribute-value pairs from an LDAP filter and returns
	 * them as a Map suitable for building REST API query strings. Useful for
	 * translating LDAP searches to REST API calls.
	 * <p>
	 * <b>Note:</b> Complex logical operators are flattened - only simple
	 * attribute=value pairs are extracted.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var ldapFilter = "(&amp;(objectClass=person)(mail=user@example.com))";
	 * var queryParams = system.ldapFilterToQueryParams(ldapFilter);
	 * // Result: {objectClass: "person", mail: "user@example.com"}
	 * 
	 * // Build URL
	 * var url = "https://api.example.com/users?";
	 * for (var key in queryParams) {
	 *     url += key + "=" + encodeURIComponent(queryParams[key]) + "&amp;";
	 * }
	 * </pre>
	 * 
	 * @param ldapFilter
	 *            The LDAP filter to convert
	 * @return Map of query parameters (empty map if conversion fails)
	 * @see #queryParamsToLDAPFilter(Map)
	 * @since 10.1
	 */
	public Map<String, String> ldapFilterToQueryParams(String ldapFilter) {
		Map<String, String> params = new HashMap<>();
		if (ldapFilter == null || ldapFilter.isEmpty())
			return params;

		try {
			parseLDAPToQueryParams(ldapFilter, params);
		} catch (Exception e) {
			lastError = e;
		}

		return params;
	}

	/**
	 * Helper method to parse LDAP filter to query parameters.
	 */
	private void parseLDAPToQueryParams(String filter, Map<String, String> params) {
		filter = filter.trim();
		if (filter.startsWith("(") && filter.endsWith(")")) {
			filter = filter.substring(1, filter.length() - 1);
		}

		// Skip logical operators for simple query params
		if (filter.startsWith("&") || filter.startsWith("|")) {
			int depth = 0;
			StringBuilder current = new StringBuilder();

			for (char c : filter.substring(1).toCharArray()) {
				if (c == '(')
					depth++;
				else if (c == ')')
					depth--;

				current.append(c);

				if (depth == 0 && current.length() > 0) {
					parseLDAPToQueryParams(current.toString(), params);
					current = new StringBuilder();
				}
			}
			return;
		}

		// Parse simple filter
		if (filter.contains("=")) {
			String[] parts = filter.split("=", 2);
			String attr = parts[0].trim();
			String value = parts[1].trim();

			if (!value.equals("*")) {
				value = value.replace("*", "");
				params.put(attr, value);
			}
		}
	}

	/**
	 * Converts REST API query parameters to an LDAP filter.
	 * <p>
	 * This method builds an LDAP filter from a Map of query parameters, combining
	 * them with AND logic. Useful for translating REST API searches to LDAP
	 * queries.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var params = new java.util.HashMap();
	 * params.put("objectClass", "person");
	 * params.put("mail", "user@example.com");
	 * 
	 * var ldapFilter = system.queryParamsToLDAPFilter(params);
	 * // Result: "(&amp;(objectClass=person)(mail=user@example.com))"
	 * </pre>
	 * 
	 * @param params
	 *            Map of query parameters
	 * @return LDAP filter, or null if params is null/empty
	 * @see #ldapFilterToQueryParams(String)
	 * @since 10.1
	 */
	public String queryParamsToLDAPFilter(Map<String, String> params) {
		if (params == null || params.isEmpty())
			return null;

		try {
			if (params.size() == 1) {
				Map.Entry<String, String> entry = params.entrySet().iterator().next();
				return "(" + entry.getKey() + "=" + entry.getValue() + ")";
			}

			StringBuilder filter = new StringBuilder("(&");
			for (Map.Entry<String, String> entry : params.entrySet()) {
				filter.append("(").append(entry.getKey()).append("=").append(entry.getValue()).append(")");
			}
			filter.append(")");

			return filter.toString();
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Validates an LDAP filter for correct syntax.
	 * <p>
	 * This method checks if an LDAP filter has valid syntax by verifying balanced
	 * parentheses and valid operators. Use this before executing searches to catch
	 * syntax errors early.
	 * <p>
	 * <b>Validation Checks:</b>
	 * <ul>
	 * <li>Balanced parentheses</li>
	 * <li>Valid logical operators (&amp;, |, !)</li>
	 * <li>Valid comparison operators (=, &gt;=, &lt;=, ~=, :=)</li>
	 * <li>Proper filter structure</li>
	 * </ul>
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var filter = "(&amp;(objectClass=person)(mail=*))";
	 * if (system.validateLDAPFilter(filter)) {
	 *     var results = conn.search(filter);
	 * } else {
	 *     task.logmsg("Invalid LDAP filter: " + filter);
	 * }
	 * </pre>
	 * 
	 * @param filter
	 *            The LDAP filter to validate
	 * @return true if filter is valid, false otherwise
	 * @see #optimizeLDAPFilter(String)
	 * @since 10.1
	 */
	public boolean validateLDAPFilter(String filter) {
		if (filter == null || filter.isEmpty())
			return false;

		try {
			// Check balanced parentheses
			int depth = 0;
			for (char c : filter.toCharArray()) {
				if (c == '(')
					depth++;
				else if (c == ')')
					depth--;
				if (depth < 0)
					return false;
			}
			if (depth != 0)
				return false;

			// Check valid operators
			String trimmed = filter.trim();
			if (trimmed.startsWith("(") && trimmed.endsWith(")")) {
				String inner = trimmed.substring(1, trimmed.length() - 1);

				// Check for valid logical operators
				if (inner.startsWith("&") || inner.startsWith("|") || inner.startsWith("!")) {
					return true;
				}

				// Check for valid comparison
				if (inner.contains("=") || inner.contains(">=") || inner.contains("<=") || inner.contains("~=")
						|| inner.contains(":=")) {
					return true;
				}
			}

			return false;
		} catch (Exception e) {
			lastError = e;
			return false;
		}
	}

	/**
	 * Optimizes an LDAP filter by removing redundant constructs.
	 * <p>
	 * This method simplifies LDAP filters by removing unnecessary parentheses,
	 * single-condition AND/OR operators, and double negations. This can improve
	 * search performance and readability.
	 * <p>
	 * <b>Optimizations Applied:</b>
	 * <ul>
	 * <li>(&amp;(condition)) → (condition)</li>
	 * <li>(|(condition)) → (condition)</li>
	 * <li>(!(!(condition))) → (condition)</li>
	 * </ul>
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var filter = "(&amp;(objectClass=person))";
	 * var optimized = system.optimizeLDAPFilter(filter);
	 * // Result: "(objectClass=person)"
	 * </pre>
	 * 
	 * @param filter
	 *            The LDAP filter to optimize
	 * @return Optimized filter, or original filter if optimization fails
	 * @see #validateLDAPFilter(String)
	 * @since 10.1
	 */
	public String optimizeLDAPFilter(String filter) {
		if (filter == null || filter.isEmpty())
			return filter;

		try {
			filter = filter.trim();

			// Optimize single-condition AND/OR
			if (filter.matches("\\(&\\([^)]+\\)\\)")) {
				// (&(condition)) -> (condition)
				return filter.substring(2, filter.length() - 1);
			}
			if (filter.matches("\\(\\|\\([^)]+\\)\\)")) {
				// (|(condition)) -> (condition)
				return filter.substring(2, filter.length() - 1);
			}

			// Remove double negation
			if (filter.matches("\\(!\\(!.*\\)\\)")) {
				// (!(!(condition))) -> (condition)
				return filter.substring(3, filter.length() - 2);
			}

			return filter;
		} catch (Exception e) {
			lastError = e;
			return filter;
		}
	}

	/**
	 * Combines multiple LDAP filters with AND logic.
	 * <p>
	 * This method is a convenient builder for creating complex LDAP filters by
	 * combining multiple conditions with AND logic. It automatically handles the
	 * proper syntax and parentheses.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var filter1 = "(objectClass=person)";
	 * var filter2 = "(mail=*@example.com)";
	 * var filter3 = "(cn=John*)";
	 * 
	 * var combined = system.buildAndFilter(filter1, filter2, filter3);
	 * // Result: "(&amp;(objectClass=person)(mail=*@example.com)(cn=John*))"
	 * </pre>
	 * 
	 * @param filters
	 *            Variable number of LDAP filters to combine
	 * @return Combined filter with AND logic, or null if no filters provided
	 * @see #buildOrFilter(String...)
	 * @see #buildNotFilter(String)
	 * @since 10.1
	 */
	public String buildAndFilter(String... filters) {
		if (filters == null || filters.length == 0)
			return null;

		try {
			if (filters.length == 1)
				return filters[0];

			StringBuilder result = new StringBuilder("(&");
			for (String filter : filters) {
				if (filter != null && !filter.isEmpty()) {
					result.append(filter);
				}
			}
			result.append(")");

			return result.toString();
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Combines multiple LDAP filters with OR logic.
	 * <p>
	 * This method is a convenient builder for creating complex LDAP filters by
	 * combining multiple conditions with OR logic. It automatically handles the
	 * proper syntax and parentheses.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var filter1 = "(mail=*@example.com)";
	 * var filter2 = "(mail=*@test.com)";
	 * var filter3 = "(mail=*@demo.com)";
	 * 
	 * var combined = system.buildOrFilter(filter1, filter2, filter3);
	 * // Result: "(|(mail=*@example.com)(mail=*@test.com)(mail=*@demo.com))"
	 * </pre>
	 * 
	 * @param filters
	 *            Variable number of LDAP filters to combine
	 * @return Combined filter with OR logic, or null if no filters provided
	 * @see #buildAndFilter(String...)
	 * @see #buildNotFilter(String)
	 * @since 10.1
	 */
	public String buildOrFilter(String... filters) {
		if (filters == null || filters.length == 0)
			return null;

		try {
			if (filters.length == 1)
				return filters[0];

			StringBuilder result = new StringBuilder("(|");
			for (String filter : filters) {
				if (filter != null && !filter.isEmpty()) {
					result.append(filter);
				}
			}
			result.append(")");

			return result.toString();
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Negates an LDAP filter with NOT logic.
	 * <p>
	 * This method wraps a filter with NOT logic, creating the inverse of the
	 * condition. Useful for exclusion filters.
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var filter = "(objectClass=person)";
	 * var notFilter = system.buildNotFilter(filter);
	 * // Result: "(!(objectClass=person))"
	 * 
	 * // Find all non-person entries
	 * var results = conn.search(notFilter);
	 * </pre>
	 * 
	 * @param filter
	 *            The LDAP filter to negate
	 * @return Negated filter, or null if filter is null/empty
	 * @see #buildAndFilter(String...)
	 * @see #buildOrFilter(String...)
	 * @since 10.1
	 */
	public String buildNotFilter(String filter) {
		if (filter == null || filter.isEmpty())
			return null;
		return "(!" + filter + ")";
	}

	/**
	 * Converts a GraphQL filter to an LDAP filter.
	 * <p>
	 * This method provides basic support for translating GraphQL filter syntax to
	 * LDAP filter syntax. GraphQL is increasingly used in modern APIs, and this
	 * method enables integration with GraphQL-based identity systems.
	 * <p>
	 * <b>Note:</b> This is a simplified implementation that handles basic patterns.
	 * Full GraphQL filter support would require a complete JSON parser.
	 * <p>
	 * <b>Supported Patterns:</b>
	 * <ul>
	 * <li>{field: {contains: "value"}} → (field=*value*)</li>
	 * <li>{field: {equals: "value"}} → (field=value)</li>
	 * </ul>
	 * <p>
	 * <b>Example:</b>
	 * 
	 * <pre>
	 * var graphQLFilter = "{email: {contains: '@example.com'}}";
	 * var ldapFilter = system.graphQLFilterToLDAP(graphQLFilter);
	 * // Result: "(email=*@example.com*)"
	 * </pre>
	 * 
	 * @param graphQLFilter
	 *            The GraphQL filter to convert
	 * @return LDAP filter, or null if conversion fails
	 * @since 10.1
	 */
	public String graphQLFilterToLDAP(String graphQLFilter) {
		if (graphQLFilter == null || graphQLFilter.isEmpty())
			return null;

		try {
			return parseGraphQLToLDAP(graphQLFilter);
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Helper method to parse GraphQL filter to LDAP.
	 */
	private String parseGraphQLToLDAP(String graphQL) {
		if (graphQL.contains("contains")) {
			String field = extractGraphQLField(graphQL);
			String value = extractGraphQLValue(graphQL);
			return "(" + field + "=*" + value + "*)";
		} else if (graphQL.contains("equals")) {
			String field = extractGraphQLField(graphQL);
			String value = extractGraphQLValue(graphQL);
			return "(" + field + "=" + value + ")";
		}

		return graphQL;
	}

	/**
	 * Helper method to extract field name from GraphQL filter.
	 */
	private String extractGraphQLField(String graphQL) {
		int start = graphQL.indexOf("{") + 1;
		int end = graphQL.indexOf(":");
		if (start > 0 && end > start) {
			return graphQL.substring(start, end).trim();
		}
		return "";
	}

	/**
	 * Helper method to extract value from GraphQL filter.
	 */
	private String extractGraphQLValue(String graphQL) {
		int start = graphQL.lastIndexOf(":") + 1;
		int end = graphQL.lastIndexOf("}");
		if (start > 0 && end > start) {
			return graphQL.substring(start, end).trim().replace("\"", "").replace("'", "");
		}
		return "";
	}

	// ========================================================================
	// Java Reflection and Type Conversion Methods
	// ========================================================================

	/**
	 * Convert Java types that are problematic in JavaScript to compatible types.
	 * This method handles Java Long and other numeric types that exceed JavaScript's
	 * safe integer range (Number.MAX_SAFE_INTEGER = 2^53 - 1).
	 * 
	 * @param obj The object to convert
	 * @return JavaScript-compatible representation of the object
	 */
	private Object convertToJavaScriptCompatible(Object obj) {
		if (obj == null) {
			return null;
		}
		
		// Convert Long to String (JavaScript can't handle 64-bit integers accurately)
		if (obj instanceof Long) {
			return obj.toString();
		}
		
		// Convert other large numbers to strings to prevent precision loss
		if (obj instanceof Number) {
			long longValue = ((Number) obj).longValue();
			// JavaScript safe integer range: -(2^53 - 1) to (2^53 - 1)
			if (longValue > 9007199254740991L || longValue < -9007199254740991L) {
				return obj.toString();
			}
		}
		
		return obj;
	}

	/**
	 * Safely invoke a method on a Java object and convert the result to a 
	 * JavaScript-compatible type. This is particularly useful for methods that 
	 * return Java Long or other types that JavaScript cannot handle directly.
	 * <p>
	 * This method eliminates the need for Apache Commons BeanUtils and provides
	 * automatic type conversion for JavaScript compatibility.
	 * <p>
	 * <b>Example:</b>
	 * <pre>
	 * // Instead of using BeanUtils:
	 * // importPackage(Packages.org.apache.commons.beanutils);
	 * // myMethodUtils = new MethodUtils();
	 * // myRequestID = myMethodUtils.invokeMethod(myRequest, "getID", null);
	 * 
	 * // Use this method:
	 * var requestID = system.invokeMethodSafe(myRequest, "getID", null);
	 * task.logmsg("Request ID: " + requestID); // Now JavaScript-compatible
	 * </pre>
	 * 
	 * @param obj The object to invoke the method on
	 * @param methodName The name of the method to invoke
	 * @param args Optional array of arguments to pass to the method. Pass null for no arguments.
	 * @return The method result converted to a JavaScript-compatible type
	 * @throws Exception if the method invocation fails or the method does not exist
	 */
	public Object invokeMethodSafe(Object obj, String methodName, Object[] args) throws Exception {
		if (obj == null) {
			throw new IllegalArgumentException("Cannot invoke method on null object");
		}
		
		if (methodName == null || methodName.trim().isEmpty()) {
			throw new IllegalArgumentException("Method name cannot be null or empty");
		}
		
		try {
			Class<?> clazz = obj.getClass();
			Method method;
			Object result;
			
			if (args == null || args.length == 0) {
				// No arguments - simple case
				method = clazz.getMethod(methodName);
				result = method.invoke(obj);
			} else {
				// With arguments - need to determine parameter types
				Class<?>[] paramTypes = new Class<?>[args.length];
				for (int i = 0; i < args.length; i++) {
					if (args[i] == null) {
						throw new IllegalArgumentException(
							"Cannot determine type for null argument at index " + i);
					}
					paramTypes[i] = args[i].getClass();
				}
				method = clazz.getMethod(methodName, paramTypes);
				result = method.invoke(obj, args);
			}
			
			return convertToJavaScriptCompatible(result);
			
		} catch (NoSuchMethodException e) {
			lastError = e;
			throw new Exception("Method '" + methodName + "' not found on object of type " + 
							  obj.getClass().getName(), e);
		} catch (IllegalAccessException e) {
			lastError = e;
			throw new Exception("Cannot access method '" + methodName + "' on object of type " + 
							  obj.getClass().getName(), e);
		} catch (java.lang.reflect.InvocationTargetException e) {
			lastError = e;
			throw new Exception("Method '" + methodName + "' threw an exception: " + 
							  e.getCause().getMessage(), e.getCause());
		} catch (Exception e) {
			lastError = e;
			throw e;
		}
	}

	/**
	 * Get a property value from a Java object using reflection, with automatic
	 * type conversion for JavaScript compatibility. This method follows JavaBean
	 * naming conventions and tries both "get" and "is" prefixes.
	 * <p>
	 * <b>Example:</b>
	 * <pre>
	 * var id = system.getPropertySafe(myRequest, "id");
	 * var active = system.getPropertySafe(myObject, "active"); // tries isActive()
	 * </pre>
	 * 
	 * @param obj The object to get the property from
	 * @param propertyName The property name (will try getPropertyName() or isPropertyName())
	 * @return The property value converted to JavaScript-compatible type
	 * @throws Exception if the property cannot be accessed
	 */
	public Object getPropertySafe(Object obj, String propertyName) throws Exception {
		if (obj == null) {
			throw new IllegalArgumentException("Cannot get property from null object");
		}
		
		if (propertyName == null || propertyName.trim().isEmpty()) {
			throw new IllegalArgumentException("Property name cannot be null or empty");
		}
		
		// Capitalize first letter for getter method name
		String capitalizedName = propertyName.substring(0, 1).toUpperCase() + 
								propertyName.substring(1);
		
		try {
			// Try standard getter first
			String getterName = "get" + capitalizedName;
			return invokeMethodSafe(obj, getterName, null);
		} catch (Exception e) {
			// Try boolean getter
			try {
				String isGetterName = "is" + capitalizedName;
				return invokeMethodSafe(obj, isGetterName, null);
			} catch (Exception e2) {
				lastError = e2;
				throw new Exception("Property '" + propertyName + "' not found on object of type " + 
								  obj.getClass().getName() + 
								  " (tried get" + capitalizedName + "() and is" + capitalizedName + "())", e2);
			}
		}
	}

	// ========================================================================
	// Asynchronous Polling and Waiting Methods
	// ========================================================================

	/**
	 * Wait for an object's property to change from a specific value, using
	 * exponential backoff and timeout. This is useful for waiting on asynchronous
	 * operations to complete.
	 * <p>
	 * The method polls the property value at increasing intervals (exponential backoff)
	 * starting at 100ms and doubling up to a maximum of 5 seconds between checks.
	 * <p>
	 * <b>Example:</b>
	 * <pre>
	 * // Instead of:
	 * // do {
	 * //     system.sleep(1);
	 * // } while (myRequest.getStatus() == com.ibm.itim.apps.Request.IN_PROCESS)
	 * 
	 * // Use:
	 * var completed = system.waitForPropertyChange(
	 *     myRequest,
	 *     "status",
	 *     com.ibm.itim.apps.Request.IN_PROCESS,
	 *     60  // 60 second timeout
	 * );
	 * 
	 * if (!completed) {
	 *     task.logmsg("Request timed out");
	 * } else {
	 *     task.logmsg("Request completed with status: " + myRequest.getStatus());
	 * }
	 * </pre>
	 * 
	 * @param obj The object to monitor
	 * @param propertyName The property name to check (e.g., "status")
	 * @param unwantedValue The value to wait to change from
	 * @param timeoutSeconds Maximum time to wait in seconds
	 * @return true if value changed from unwantedValue, false if timeout occurred
	 * @throws Exception if monitoring fails or is interrupted
	 */
	public boolean waitForPropertyChange(Object obj, String propertyName,
										Object unwantedValue, int timeoutSeconds) throws Exception {
		if (obj == null) {
			throw new IllegalArgumentException("Cannot monitor null object");
		}
		
		if (timeoutSeconds <= 0) {
			throw new IllegalArgumentException("Timeout must be positive");
		}
		
		long startTime = System.currentTimeMillis();
		long timeoutMs = timeoutSeconds * 1000L;
		int delay = 100; // Start with 100ms
		int maxDelay = 5000; // Max 5 seconds between checks
		
		try {
			while (System.currentTimeMillis() - startTime < timeoutMs) {
				Object currentValue = getPropertySafe(obj, propertyName);
				
				// Check if value has changed
				if (currentValue == null && unwantedValue != null) {
					return true; // Changed to null
				}
				if (currentValue != null && !currentValue.equals(unwantedValue)) {
					return true; // Changed to different value
				}
				
				// Sleep before next check
				Thread.sleep(delay);
				
				// Exponential backoff
				delay = Math.min(delay * 2, maxDelay);
			}
			
			return false; // Timeout
			
		} catch (InterruptedException e) {
			lastError = e;
			throw new Exception("Wait interrupted", e);
		}
	}

	/**
	 * Wait for an object's property to reach a specific value, using exponential
	 * backoff and timeout.
	 * <p>
	 * <b>Example:</b>
	 * <pre>
	 * var completed = system.waitForPropertyValue(
	 *     myRequest,
	 *     "status",
	 *     com.ibm.itim.apps.Request.COMPLETED,
	 *     60  // 60 second timeout
	 * );
	 * </pre>
	 * 
	 * @param obj The object to monitor
	 * @param propertyName The property name to check (e.g., "status")
	 * @param expectedValue The value to wait for
	 * @param timeoutSeconds Maximum time to wait in seconds
	 * @return true if expected value was reached, false if timeout occurred
	 * @throws Exception if monitoring fails or is interrupted
	 */
	public boolean waitForPropertyValue(Object obj, String propertyName, 
									   Object expectedValue, int timeoutSeconds) throws Exception {
		if (obj == null) {
			throw new IllegalArgumentException("Cannot monitor null object");
		}
		
		if (timeoutSeconds <= 0) {
			throw new IllegalArgumentException("Timeout must be positive");
		}
		
		long startTime = System.currentTimeMillis();
		long timeoutMs = timeoutSeconds * 1000L;
		int delay = 100; // Start with 100ms
		int maxDelay = 5000; // Max 5 seconds between checks
		
		try {
			while (System.currentTimeMillis() - startTime < timeoutMs) {
				Object currentValue = getPropertySafe(obj, propertyName);
				
				// Check if value matches expected
				if (currentValue == null && expectedValue == null) {
					return true; // Both null
				}
				if (currentValue != null && currentValue.equals(expectedValue)) {
					return true; // Match found
				}
				
				// Sleep before next check
				Thread.sleep(delay);
				
				// Exponential backoff
				delay = Math.min(delay * 2, maxDelay);
			}
			
			return false; // Timeout
			
		} catch (InterruptedException e) {
			lastError = e;
			throw new Exception("Wait interrupted", e);
		}
	}

	// =========================================================================
	// Phase 4: xslTransform Enhancements
	// =========================================================================

	/**
	 * Transforms an XML document using an XSL stylesheet with runtime parameters.
	 * <p>
	 * Parameters are passed to the XSLT processor and accessed via
	 * {@code <xsl:param name="paramName"/>} declarations in the stylesheet.
	 * This overload maintains backward compatibility with the existing
	 * {@link #xslTransform(Object, Object)} method.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var params = [
	 *     ["reportDate", new Date().toString()],
	 *     ["userName", "admin"],
	 *     ["outputFormat", "html"]
	 * ];
	 * var result = system.xslTransform(xslFile, xmlFile, params);
	 * </pre>
	 *
	 * @param xsl
	 *            The XSL stylesheet (String filename, String with newlines,
	 *            java.io.File, java.io.Reader, java.io.InputStream, or StreamSource)
	 * @param xml
	 *            The XML document (same types as xsl)
	 * @param params
	 *            Array of parameter name-value pairs. Each element must be a
	 *            two-element array: {@code [["name1","val1"],["name2","val2"]]}
	 *            Pass null to perform transformation without parameters.
	 * @return The transformed document as a String, or null on error
	 * @see #xslTransform(Object, Object)
	 * @see #xslTransform(Object, Object, Object[][], String)
	 * @see #xslTransformToFile(Object, Object, Object[][], String)
	 * @see #lastError
	 * @since 10.1
	 */
	public String xslTransform(Object xsl, Object xml, Object[][] params) {
		return xslTransform(xsl, xml, params, (String) null);
	}

	/**
	 * Transforms an XML document using an XSL stylesheet with parameters and
	 * explicit output encoding control.
	 * <p>
	 * This overload allows overriding the encoding declared in the XSLT
	 * {@code <xsl:output>} element, ensuring consistent encoding across
	 * transformations regardless of stylesheet declarations.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var params = [["title", "My Report"]];
	 * var result = system.xslTransform(xslFile, xmlFile, params, "UTF-8");
	 * </pre>
	 *
	 * @param xsl
	 *            The XSL stylesheet
	 * @param xml
	 *            The XML document
	 * @param params
	 *            Array of parameter name-value pairs, or null
	 * @param outputEncoding
	 *            Output encoding (e.g. "UTF-8", "ISO-8859-1"), or null to use
	 *            the encoding declared in the stylesheet
	 * @return The transformed document as a String, or null on error
	 * @see #xslTransform(Object, Object, Object[][])
	 * @see #lastError
	 * @since 10.1
	 */
	public String xslTransform(Object xsl, Object xml, Object[][] params, String outputEncoding) {
		try {
			TransformerFactory transfactory = TransformerFactory.newInstance();
			ErrorListenerImpl el = new ErrorListenerImpl();
			transfactory.setErrorListener(el);
			Transformer transformer = transfactory.newTransformer(getStreamSource(xsl));
			if (el.excep != null)
				throw el.excep;

			// Apply parameters
			if (params != null) {
				for (Object[] param : params) {
					if (param != null && param.length >= 2 && param[0] != null) {
						transformer.setParameter(param[0].toString(),
								param[1] != null ? param[1].toString() : "");
					}
				}
			}

			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			el.excep = null;
			transformer.setErrorListener(el);
			transformer.transform(getStreamSource(xml), new StreamResult(bos));
			if (el.excep != null)
				throw el.excep;

			// Resolve encoding: explicit override > stylesheet declaration
			if (outputEncoding != null && !outputEncoding.isEmpty()) {
				return bos.toString(outputEncoding);
			}
			String declaredEncoding = transformer.getOutputProperty("encoding");
			if (declaredEncoding != null) {
				return bos.toString(declaredEncoding);
			}
			return bos.toString();
		} catch (Exception error) {
			lastError = error;
			return null;
		}
	}

	/**
	 * Transforms an XML document using an XSL stylesheet with parameters and
	 * runtime output property overrides.
	 * <p>
	 * Output properties correspond to standard JAXP {@link OutputKeys} constants
	 * (e.g. {@code "indent"}, {@code "method"}, {@code "omit-xml-declaration"},
	 * {@code "encoding"}). Properties set here override those declared in the
	 * stylesheet's {@code <xsl:output>} element.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var params = [["title", "Report"]];
	 * var config = new java.util.Properties();
	 * config.setProperty("indent", "yes");
	 * config.setProperty("omit-xml-declaration", "no");
	 * config.setProperty("method", "html");
	 * var result = system.xslTransform(xslFile, xmlFile, params, config);
	 * </pre>
	 *
	 * @param xsl
	 *            The XSL stylesheet
	 * @param xml
	 *            The XML document
	 * @param params
	 *            Array of parameter name-value pairs, or null
	 * @param outputProperties
	 *            Output property overrides as a {@link java.util.Properties}
	 *            object, or null for stylesheet defaults
	 * @return The transformed document as a String, or null on error
	 * @see #xslTransform(Object, Object, Object[][], String)
	 * @see #lastError
	 * @since 10.1
	 */
	public String xslTransform(Object xsl, Object xml, Object[][] params, Properties outputProperties) {
		try {
			TransformerFactory transfactory = TransformerFactory.newInstance();
			ErrorListenerImpl el = new ErrorListenerImpl();
			transfactory.setErrorListener(el);
			Transformer transformer = transfactory.newTransformer(getStreamSource(xsl));
			if (el.excep != null)
				throw el.excep;

			// Apply output property overrides
			if (outputProperties != null) {
				for (String key : outputProperties.stringPropertyNames()) {
					transformer.setOutputProperty(key, outputProperties.getProperty(key));
				}
			}

			// Apply parameters
			if (params != null) {
				for (Object[] param : params) {
					if (param != null && param.length >= 2 && param[0] != null) {
						transformer.setParameter(param[0].toString(),
								param[1] != null ? param[1].toString() : "");
					}
				}
			}

			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			el.excep = null;
			transformer.setErrorListener(el);
			transformer.transform(getStreamSource(xml), new StreamResult(bos));
			if (el.excep != null)
				throw el.excep;

			String enc = transformer.getOutputProperty("encoding");
			return (enc != null) ? bos.toString(enc) : bos.toString();
		} catch (Exception error) {
			lastError = error;
			return null;
		}
	}

	/**
	 * Transforms an XML document and writes the result directly to a file.
	 * <p>
	 * More memory-efficient than the String-returning overloads for large
	 * transformations, as the output is streamed directly to disk without being
	 * buffered in memory.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var params = [["title", "Annual Report"]];
	 * var ok = system.xslTransformToFile(xslFile, xmlFile, params, "/output/report.html");
	 * if (!ok) task.logmsg("Transform failed: " + system.lastError);
	 * </pre>
	 *
	 * @param xsl
	 *            The XSL stylesheet
	 * @param xml
	 *            The XML document
	 * @param params
	 *            Array of parameter name-value pairs, or null
	 * @param outputFile
	 *            Path to the output file; parent directories must exist
	 * @return true if the transformation succeeded, false on error
	 * @see #xslTransform(Object, Object, Object[][])
	 * @see #lastError
	 * @since 10.1
	 */
	public boolean xslTransformToFile(Object xsl, Object xml, Object[][] params, String outputFile) {
		try {
			TransformerFactory transfactory = TransformerFactory.newInstance();
			ErrorListenerImpl el = new ErrorListenerImpl();
			transfactory.setErrorListener(el);
			Transformer transformer = transfactory.newTransformer(getStreamSource(xsl));
			if (el.excep != null)
				throw el.excep;

			if (params != null) {
				for (Object[] param : params) {
					if (param != null && param.length >= 2 && param[0] != null) {
						transformer.setParameter(param[0].toString(),
								param[1] != null ? param[1].toString() : "");
					}
				}
			}

			el.excep = null;
			transformer.setErrorListener(el);
			transformer.transform(getStreamSource(xml), new StreamResult(new File(outputFile)));
			if (el.excep != null)
				throw el.excep;

			return true;
		} catch (Exception error) {
			lastError = error;
			return false;
		}
	}

	/**
	 * Transforms an XML document with a custom base URI for resolving relative
	 * references in {@code xsl:import} and {@code xsl:include} directives.
	 * <p>
	 * Useful when working with modular stylesheets split across multiple files,
	 * where import paths are relative to a base directory rather than the
	 * process working directory.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var params = [["lang", "en"]];
	 * var result = system.xslTransformWithBase(xslFile, xmlFile, params,
	 *                   "file:///opt/tdi/stylesheets/");
	 * </pre>
	 *
	 * @param xsl
	 *            The XSL stylesheet
	 * @param xml
	 *            The XML document
	 * @param params
	 *            Array of parameter name-value pairs, or null
	 * @param baseUri
	 *            Base URI for resolving relative stylesheet references
	 * @return The transformed document as a String, or null on error
	 * @see #xslTransform(Object, Object, Object[][])
	 * @see #lastError
	 * @since 10.1
	 */
	public String xslTransformWithBase(Object xsl, Object xml, Object[][] params, String baseUri) {
		try {
			TransformerFactory transfactory = TransformerFactory.newInstance();
			ErrorListenerImpl el = new ErrorListenerImpl();
			transfactory.setErrorListener(el);

			if (baseUri != null && !baseUri.isEmpty()) {
				final String base = baseUri;
				transfactory.setURIResolver(new URIResolver() {
					public javax.xml.transform.Source resolve(String href, String unused)
							throws javax.xml.transform.TransformerException {
						return new StreamSource(base + href);
					}
				});
			}

			Transformer transformer = transfactory.newTransformer(getStreamSource(xsl));
			if (el.excep != null)
				throw el.excep;

			if (params != null) {
				for (Object[] param : params) {
					if (param != null && param.length >= 2 && param[0] != null) {
						transformer.setParameter(param[0].toString(),
								param[1] != null ? param[1].toString() : "");
					}
				}
			}

			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			el.excep = null;
			transformer.setErrorListener(el);
			transformer.transform(getStreamSource(xml), new StreamResult(bos));
			if (el.excep != null)
				throw el.excep;

			String enc = transformer.getOutputProperty("encoding");
			return (enc != null) ? bos.toString(enc) : bos.toString();
		} catch (Exception error) {
			lastError = error;
			return null;
		}
	}

	/** Cache for compiled XSLT templates, keyed by user-supplied cache key. */
	private final Map<String, javax.xml.transform.Templates> xslTemplateCache =
			new ConcurrentHashMap<>();

	/**
	 * Transforms an XML document using a cached compiled XSLT transformer.
	 * <p>
	 * Compiling an XSLT stylesheet is expensive. When the same stylesheet is
	 * used repeatedly (e.g. inside an Assembly Line loop), providing a
	 * {@code cacheKey} causes the compiled {@link javax.xml.transform.Templates}
	 * object to be stored and reused, significantly reducing CPU overhead.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * // First call compiles; subsequent calls reuse the compiled template
	 * var params = [["date", new Date().toString()]];
	 * var result = system.xslTransformCached(xslFile, xmlDoc, params, "myReport");
	 * </pre>
	 *
	 * @param xsl
	 *            The XSL stylesheet
	 * @param xml
	 *            The XML document
	 * @param params
	 *            Array of parameter name-value pairs, or null
	 * @param cacheKey
	 *            Unique string key identifying this stylesheet in the cache.
	 *            Pass null to disable caching (compiles every time).
	 * @return The transformed document as a String, or null on error
	 * @see #xslTransform(Object, Object, Object[][])
	 * @see #lastError
	 * @since 10.1
	 */
	public String xslTransformCached(Object xsl, Object xml, Object[][] params, String cacheKey) {
		try {
			javax.xml.transform.Templates templates = null;

			if (cacheKey != null) {
				templates = xslTemplateCache.get(cacheKey);
			}

			if (templates == null) {
				TransformerFactory transfactory = TransformerFactory.newInstance();
				ErrorListenerImpl el = new ErrorListenerImpl();
				transfactory.setErrorListener(el);
				templates = transfactory.newTemplates(getStreamSource(xsl));
				if (el.excep != null)
					throw el.excep;
				if (cacheKey != null) {
					xslTemplateCache.put(cacheKey, templates);
				}
			}

			Transformer transformer = templates.newTransformer();
			ErrorListenerImpl el2 = new ErrorListenerImpl();
			transformer.setErrorListener(el2);

			if (params != null) {
				for (Object[] param : params) {
					if (param != null && param.length >= 2 && param[0] != null) {
						transformer.setParameter(param[0].toString(),
								param[1] != null ? param[1].toString() : "");
					}
				}
			}

			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			transformer.transform(getStreamSource(xml), new StreamResult(bos));
			if (el2.excep != null)
				throw el2.excep;

			String enc = transformer.getOutputProperty("encoding");
			return (enc != null) ? bos.toString(enc) : bos.toString();
		} catch (Exception error) {
			lastError = error;
			return null;
		}
	}

	/**
	 * Clears all entries from the XSL transformer cache.
	 * <p>
	 * Call this method when stylesheets have been updated on disk and you need
	 * the next {@link #xslTransformCached} call to recompile them.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * system.clearXslCache();
	 * </pre>
	 *
	 * @see #xslTransformCached(Object, Object, Object[][], String)
	 * @since 10.1
	 */
	public void clearXslCache() {
		xslTemplateCache.clear();
	}

	// =========================================================================
	// Phase 4: String Manipulation Methods
	// =========================================================================

	/**
	 * Splits a string with advanced control over empty tokens and trimming.
	 * <p>
	 * Unlike the existing {@link #splitString(String, String)} which uses
	 * {@code StringTokenizer} and silently drops empty tokens, this method
	 * supports preserving empty tokens and optional whitespace trimming.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * // Preserve empty tokens
	 * var parts = system.splitStringAdvanced("a,,b,c", ",", -1, true, false);
	 * // Result: ["a", "", "b", "c"]
	 *
	 * // Trim each token
	 * var parts = system.splitStringAdvanced(" a , b , c ", ",", -1, false, true);
	 * // Result: ["a", "b", "c"]
	 * </pre>
	 *
	 * @param source
	 *            The string to split
	 * @param delimiter
	 *            Literal delimiter string
	 * @param limit
	 *            Maximum number of tokens (-1 for unlimited)
	 * @param preserveEmpty
	 *            If true, empty tokens between consecutive delimiters are kept
	 * @param trim
	 *            If true, each token is trimmed of surrounding whitespace
	 * @return Array of tokens, or null on error
	 * @see #splitString(String, String)
	 * @see #splitStringRegex(String, String, int)
	 * @since 10.1
	 */
	public String[] splitStringAdvanced(String source, String delimiter, int limit,
			boolean preserveEmpty, boolean trim) {
		if (source == null)
			return null;
		try {
			String[] parts;
			if (limit > 0) {
				parts = source.split(Pattern.quote(delimiter), limit);
			} else {
				parts = source.split(Pattern.quote(delimiter), -1);
			}
			List<String> result = new ArrayList<>();
			for (String part : parts) {
				String token = trim ? part.trim() : part;
				if (preserveEmpty || !token.isEmpty()) {
					result.add(token);
				}
			}
			return result.toArray(new String[0]);
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Splits a string using a regular expression pattern.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * // Split on one or more whitespace characters
	 * var words = system.splitStringRegex("hello   world  foo", "\\s+", -1);
	 * // Result: ["hello", "world", "foo"]
	 * </pre>
	 *
	 * @param source
	 *            The string to split
	 * @param regexPattern
	 *            Regular expression to split on
	 * @param limit
	 *            Maximum number of tokens (-1 for unlimited)
	 * @return Array of tokens, or null on error
	 * @see #splitStringAdvanced(String, String, int, boolean, boolean)
	 * @since 10.1
	 */
	public String[] splitStringRegex(String source, String regexPattern, int limit) {
		if (source == null)
			return null;
		try {
			return limit > 0 ? source.split(regexPattern, limit) : source.split(regexPattern, -1);
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Joins an array of objects into a single string with a delimiter.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var result = system.joinStrings(["a", "b", "c"], ", ");
	 * // Result: "a, b, c"
	 * </pre>
	 *
	 * @param array
	 *            Array of objects whose {@code toString()} values are joined;
	 *            null elements are represented as empty strings
	 * @param delimiter
	 *            Separator placed between each element
	 * @return Joined string, or null on error
	 * @since 10.1
	 */
	public String joinStrings(Object[] array, String delimiter) {
		if (array == null)
			return null;
		try {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < array.length; i++) {
				if (i > 0)
					sb.append(delimiter != null ? delimiter : "");
				sb.append(array[i] != null ? array[i].toString() : "");
			}
			return sb.toString();
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Left-pads a string to the specified length with a pad character.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var padded = system.padLeft("42", 6, "0");
	 * // Result: "000042"
	 * </pre>
	 *
	 * @param str
	 *            The string to pad
	 * @param length
	 *            Target total length; if str is already this long or longer, it
	 *            is returned unchanged
	 * @param padChar
	 *            Single character used for padding
	 * @return Padded string
	 * @see #padRight(String, int, String)
	 * @since 10.1
	 */
	public String padLeft(String str, int length, String padChar) {
		if (str == null)
			str = "";
		if (padChar == null || padChar.isEmpty())
			padChar = " ";
		String pad = padChar.substring(0, 1);
		StringBuilder sb = new StringBuilder();
		for (int i = str.length(); i < length; i++)
			sb.append(pad);
		sb.append(str);
		return sb.toString();
	}

	/**
	 * Right-pads a string to the specified length with a pad character.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var padded = system.padRight("hello", 10, "-");
	 * // Result: "hello-----"
	 * </pre>
	 *
	 * @param str
	 *            The string to pad
	 * @param length
	 *            Target total length
	 * @param padChar
	 *            Single character used for padding
	 * @return Padded string
	 * @see #padLeft(String, int, String)
	 * @since 10.1
	 */
	public String padRight(String str, int length, String padChar) {
		if (str == null)
			str = "";
		if (padChar == null || padChar.isEmpty())
			padChar = " ";
		String pad = padChar.substring(0, 1);
		StringBuilder sb = new StringBuilder(str);
		while (sb.length() < length)
			sb.append(pad);
		return sb.toString();
	}

	/**
	 * Truncates a string to a maximum length, appending a suffix when truncated.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var result = system.truncateString("Hello World", 8, "...");
	 * // Result: "Hello..."
	 * </pre>
	 *
	 * @param str
	 *            The string to truncate
	 * @param maxLength
	 *            Maximum total length of the returned string (including suffix)
	 * @param suffix
	 *            Appended when truncation occurs (e.g. {@code "..."})
	 * @return Truncated string
	 * @since 10.1
	 */
	public String truncateString(String str, int maxLength, String suffix) {
		if (str == null)
			return null;
		if (str.length() <= maxLength)
			return str;
		String sfx = (suffix != null) ? suffix : "";
		int cutAt = maxLength - sfx.length();
		if (cutAt < 0)
			cutAt = 0;
		return str.substring(0, cutAt) + sfx;
	}

	/**
	 * Replaces all occurrences of a regex pattern with a replacement string.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var result = system.replaceAllRegex("foo123bar456", "\\d+", "#");
	 * // Result: "foo#bar#"
	 * </pre>
	 *
	 * @param source
	 *            The source string
	 * @param regexPattern
	 *            Regular expression pattern to match
	 * @param replacement
	 *            Replacement string (supports {@code $1} back-references)
	 * @return Result string, or null on error
	 * @since 10.1
	 */
	public String replaceAllRegex(String source, String regexPattern, String replacement) {
		if (source == null)
			return null;
		try {
			return source.replaceAll(regexPattern, replacement != null ? replacement : "");
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Tests whether a string fully matches a regular expression pattern.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * if (system.matchesRegex(email, "^[\\w._%+\\-]+@[\\w.\\-]+\\.[a-z]{2,}$")) {
	 *     // valid email
	 * }
	 * </pre>
	 *
	 * @param source
	 *            The string to test
	 * @param regexPattern
	 *            Regular expression pattern
	 * @return true if the entire string matches the pattern
	 * @since 10.1
	 */
	public boolean matchesRegex(String source, String regexPattern) {
		if (source == null || regexPattern == null)
			return false;
		try {
			return source.matches(regexPattern);
		} catch (Exception e) {
			lastError = e;
			return false;
		}
	}

	/**
	 * Extracts the first match of a regex group from a string.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * // Extract the domain from an email
	 * var domain = system.extractRegex("user@example.com", "@(.+)$", 1);
	 * // Result: "example.com"
	 * </pre>
	 *
	 * @param source
	 *            The string to search
	 * @param regexPattern
	 *            Regular expression pattern containing capture groups
	 * @param groupIndex
	 *            Capture group index (0 = entire match, 1 = first group, etc.)
	 * @return Matched text, or null if no match
	 * @since 10.1
	 */
	public String extractRegex(String source, String regexPattern, int groupIndex) {
		if (source == null || regexPattern == null)
			return null;
		try {
			Matcher m = Pattern.compile(regexPattern).matcher(source);
			if (m.find()) {
				return m.group(groupIndex);
			}
			return null;
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Case-insensitive substring check.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * if (system.containsIgnoreCase(description, "admin")) { ... }
	 * </pre>
	 *
	 * @param source
	 *            The string to search within
	 * @param search
	 *            The substring to look for
	 * @return true if source contains search (case-insensitive)
	 * @since 10.1
	 */
	public boolean containsIgnoreCase(String source, String search) {
		if (source == null || search == null)
			return false;
		return source.toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT));
	}

	/**
	 * Returns the index of the first case-insensitive occurrence of a substring.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var idx = system.indexOfIgnoreCase("Hello World", "world");
	 * // Result: 6
	 * </pre>
	 *
	 * @param source
	 *            The string to search within
	 * @param search
	 *            The substring to find
	 * @return Index of first occurrence, or -1 if not found
	 * @since 10.1
	 */
	public int indexOfIgnoreCase(String source, String search) {
		if (source == null || search == null)
			return -1;
		return source.toLowerCase(Locale.ROOT).indexOf(search.toLowerCase(Locale.ROOT));
	}

	/**
	 * Replaces the first occurrence of a literal target string.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var result = system.replaceFirst("aabbcc", "b", "X");
	 * // Result: "aaXbcc"
	 * </pre>
	 *
	 * @param source
	 *            The source string
	 * @param target
	 *            Literal string to find (not a regex)
	 * @param replacement
	 *            Replacement value
	 * @return Result string
	 * @since 10.1
	 */
	public String replaceFirst(String source, String target, String replacement) {
		if (source == null)
			return null;
		if (target == null || target.isEmpty())
			return source;
		int idx = source.indexOf(target);
		if (idx < 0)
			return source;
		return source.substring(0, idx)
				+ (replacement != null ? replacement : "")
				+ source.substring(idx + target.length());
	}

	/**
	 * Reverses a string.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var result = system.reverse("hello");
	 * // Result: "olleh"
	 * </pre>
	 *
	 * @param str
	 *            The string to reverse
	 * @return Reversed string, or null if input is null
	 * @since 10.1
	 */
	public String reverse(String str) {
		if (str == null)
			return null;
		return new StringBuilder(str).reverse().toString();
	}

	/**
	 * Repeats a string a specified number of times.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var line = system.repeat("-", 40);
	 * // Result: "----------------------------------------"
	 * </pre>
	 *
	 * @param str
	 *            The string to repeat
	 * @param count
	 *            Number of repetitions (0 returns empty string)
	 * @return Repeated string
	 * @since 10.1
	 */
	public String repeat(String str, int count) {
		if (str == null || count <= 0)
			return "";
		StringBuilder sb = new StringBuilder(str.length() * count);
		for (int i = 0; i < count; i++)
			sb.append(str);
		return sb.toString();
	}

	// =========================================================================
	// Phase 4: Numeric Utility Methods
	// =========================================================================

	/**
	 * Converts a string to a Double value.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var amount = system.toDouble("3.14159");
	 * </pre>
	 *
	 * @param str
	 *            String representation of a double value
	 * @return Double object, or null on parse failure
	 * @see #isValidDouble(String)
	 * @since 10.1
	 */
	public Double toDouble(String str) {
		if (str == null)
			return null;
		try {
			return Double.parseDouble(str.trim());
		} catch (NumberFormatException e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Tests whether a string represents a valid double value.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * if (system.isValidDouble(input)) {
	 *     var d = system.toDouble(input);
	 * }
	 * </pre>
	 *
	 * @param str
	 *            String to validate
	 * @return true if the string can be parsed as a double
	 * @see #toDouble(String)
	 * @since 10.1
	 */
	public boolean isValidDouble(String str) {
		if (str == null)
			return false;
		try {
			Double.parseDouble(str.trim());
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	/**
	 * Formats a number to a specified number of decimal places.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var formatted = system.formatNumber(3.14159, 2);
	 * // Result: "3.14"
	 * </pre>
	 *
	 * @param number
	 *            The number to format
	 * @param decimalPlaces
	 *            Number of decimal places (0 for integers)
	 * @return Formatted string
	 * @since 10.1
	 */
	public String formatNumber(double number, int decimalPlaces) {
		try {
			java.text.DecimalFormat df = new java.text.DecimalFormat();
			df.setMinimumFractionDigits(decimalPlaces);
			df.setMaximumFractionDigits(decimalPlaces);
			df.setGroupingUsed(false);
			return df.format(number);
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Rounds a double value to the specified number of decimal places.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var result = system.roundNumber(3.14159, 2);
	 * // Result: 3.14
	 * </pre>
	 *
	 * @param number
	 *            The number to round
	 * @param decimalPlaces
	 *            Number of decimal places
	 * @return Rounded value
	 * @since 10.1
	 */
	public double roundNumber(double number, int decimalPlaces) {
		double scale = Math.pow(10, decimalPlaces);
		return Math.round(number * scale) / scale;
	}

	/**
	 * Clamps a value to the inclusive range [{@code min}, {@code max}].
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var score = system.clampNumber(rawScore, 0.0, 100.0);
	 * </pre>
	 *
	 * @param value
	 *            The value to clamp
	 * @param min
	 *            Minimum allowed value (inclusive)
	 * @param max
	 *            Maximum allowed value (inclusive)
	 * @return Clamped value
	 * @since 10.1
	 */
	public double clampNumber(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	/**
	 * Tests whether a string represents any numeric value (integer or decimal).
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * if (system.isNumeric(work.getString("amount"))) {
	 *     // safe to parse
	 * }
	 * </pre>
	 *
	 * @param str
	 *            String to test
	 * @return true if the string is numeric
	 * @since 10.1
	 */
	public boolean isNumeric(String str) {
		if (str == null || str.trim().isEmpty())
			return false;
		try {
			Double.parseDouble(str.trim());
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	/**
	 * Returns a random double in the range [{@code min}, {@code max}).
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var delay = system.randomNumber(1000, 5000); // random ms between 1-5 sec
	 * </pre>
	 *
	 * @param min
	 *            Minimum value (inclusive)
	 * @param max
	 *            Maximum value (exclusive)
	 * @return Random double in [min, max)
	 * @since 10.1
	 */
	public double randomNumber(double min, double max) {
		return min + (new Random().nextDouble() * (max - min));
	}

	// =========================================================================
	// Phase 4: Date/Time Enhancement Methods
	// =========================================================================

	/**
	 * Parses a date string using the specified format, timezone and locale.
	 * <p>
	 * Extends the existing {@link #parseDate(String, String)} with timezone and
	 * locale awareness, and an optional lenient parsing mode.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var d = system.parseDate("2024-06-01 15:30:00", "yyyy-MM-dd HH:mm:ss",
	 *                          "America/New_York", "en_US", false);
	 * </pre>
	 *
	 * @param value
	 *            The date string to parse
	 * @param format
	 *            Date/time format pattern (SimpleDateFormat)
	 * @param timezone
	 *            Timezone ID (e.g. "UTC", "America/New_York"), or null for system default
	 * @param locale
	 *            Locale string (e.g. "en_US", "de_DE"), or null for system default
	 * @param lenient
	 *            If true, permits slightly out-of-range values (e.g. month 13)
	 * @return Parsed Date object, or null on error
	 * @see #parseDate(String, String)
	 * @since 10.1
	 */
	public Date parseDate(String value, String format, String timezone, String locale, boolean lenient) {
		if (value == null || format == null)
			return null;
		try {
			Locale loc = (locale != null) ? new Locale(locale.replace("-", "_").split("_")[0],
					locale.contains("_") ? locale.split("_")[1] : "") : Locale.getDefault();
			java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(format, loc);
			sdf.setLenient(lenient);
			if (timezone != null && !timezone.isEmpty()) {
				sdf.setTimeZone(java.util.TimeZone.getTimeZone(timezone));
			}
			return sdf.parse(value);
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Formats a Date object using the specified format, timezone and locale.
	 * <p>
	 * Extends the existing {@link #formatDate(Date, String)} with timezone and
	 * locale awareness.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var str = system.formatDate(new Date(), "yyyy-MM-dd HH:mm:ss z",
	 *                             "UTC", "en_US");
	 * </pre>
	 *
	 * @param date
	 *            The Date to format
	 * @param format
	 *            Date/time format pattern (SimpleDateFormat)
	 * @param timezone
	 *            Timezone ID, or null for system default
	 * @param locale
	 *            Locale string, or null for system default
	 * @return Formatted date string, or null on error
	 * @see #formatDate(Date, String)
	 * @since 10.1
	 */
	public String formatDate(Date date, String format, String timezone, String locale) {
		if (date == null || format == null)
			return null;
		try {
			Locale loc = (locale != null) ? new Locale(locale.replace("-", "_").split("_")[0],
					locale.contains("_") ? locale.split("_")[1] : "") : Locale.getDefault();
			java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(format, loc);
			if (timezone != null && !timezone.isEmpty()) {
				sdf.setTimeZone(java.util.TimeZone.getTimeZone(timezone));
			}
			return sdf.format(date);
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Adds a number of days to a Date.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var tomorrow = system.addDays(new Date(), 1);
	 * var lastWeek = system.addDays(new Date(), -7);
	 * </pre>
	 *
	 * @param date
	 *            The base date
	 * @param days
	 *            Number of days to add (negative to subtract)
	 * @return New Date with days added
	 * @see #addHours(Date, int)
	 * @see #addMonths(Date, int)
	 * @since 10.1
	 */
	public Date addDays(Date date, int days) {
		if (date == null)
			return null;
		Calendar cal = Calendar.getInstance();
		cal.setTime(date);
		cal.add(Calendar.DAY_OF_MONTH, days);
		return cal.getTime();
	}

	/**
	 * Adds a number of hours to a Date.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var inTwoHours = system.addHours(new Date(), 2);
	 * </pre>
	 *
	 * @param date
	 *            The base date
	 * @param hours
	 *            Number of hours to add (negative to subtract)
	 * @return New Date with hours added
	 * @see #addDays(Date, int)
	 * @since 10.1
	 */
	public Date addHours(Date date, int hours) {
		if (date == null)
			return null;
		Calendar cal = Calendar.getInstance();
		cal.setTime(date);
		cal.add(Calendar.HOUR_OF_DAY, hours);
		return cal.getTime();
	}

	/**
	 * Adds a number of months to a Date.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var nextQuarter = system.addMonths(new Date(), 3);
	 * </pre>
	 *
	 * @param date
	 *            The base date
	 * @param months
	 *            Number of months to add (negative to subtract)
	 * @return New Date with months added
	 * @see #addDays(Date, int)
	 * @since 10.1
	 */
	public Date addMonths(Date date, int months) {
		if (date == null)
			return null;
		Calendar cal = Calendar.getInstance();
		cal.setTime(date);
		cal.add(Calendar.MONTH, months);
		return cal.getTime();
	}

	/**
	 * Returns the number of complete days between two dates.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var days = system.dateDiffDays(startDate, endDate);
	 * task.logmsg("Duration: " + days + " days");
	 * </pre>
	 *
	 * @param date1
	 *            Start date
	 * @param date2
	 *            End date
	 * @return Number of complete days between date1 and date2 (can be negative)
	 * @see #dateDiffHours(Date, Date)
	 * @since 10.1
	 */
	public long dateDiffDays(Date date1, Date date2) {
		if (date1 == null || date2 == null)
			return 0;
		long diffMs = date2.getTime() - date1.getTime();
		return diffMs / (1000L * 60 * 60 * 24);
	}

	/**
	 * Returns the number of complete hours between two dates.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var hours = system.dateDiffHours(startDate, endDate);
	 * </pre>
	 *
	 * @param date1
	 *            Start date
	 * @param date2
	 *            End date
	 * @return Number of complete hours between date1 and date2 (can be negative)
	 * @see #dateDiffDays(Date, Date)
	 * @since 10.1
	 */
	public long dateDiffHours(Date date1, Date date2) {
		if (date1 == null || date2 == null)
			return 0;
		long diffMs = date2.getTime() - date1.getTime();
		return diffMs / (1000L * 60 * 60);
	}

	/**
	 * Tests whether a given year is a leap year.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * if (system.isLeapYear(2024)) {
	 *     task.logmsg("February has 29 days");
	 * }
	 * </pre>
	 *
	 * @param year
	 *            The four-digit year to test
	 * @return true if the year is a leap year
	 * @since 10.1
	 */
	public boolean isLeapYear(int year) {
		return (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0);
	}

	// =========================================================================
	// Phase 4: File I/O Utility Methods
	// =========================================================================

	/**
	 * Reads the entire contents of a file to a String with explicit encoding.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var content = system.readFileToString("/etc/config.xml", "UTF-8");
	 * if (content == null) task.logmsg("Read failed: " + system.lastError);
	 * </pre>
	 *
	 * @param filename
	 *            Path to the file
	 * @param encoding
	 *            Character encoding (e.g. "UTF-8"), or null for platform default
	 * @return File contents as a String, or null on error
	 * @see #writeStringToFile(String, String, String, boolean)
	 * @since 10.1
	 */
	public String readFileToString(String filename, String encoding) {
		if (filename == null)
			return null;
		try {
			java.io.InputStream is = new FileInputStream(filename);
			InputStreamReader isr = (encoding != null)
					? new InputStreamReader(is, encoding)
					: new InputStreamReader(is);
			BufferedReader br = new BufferedReader(isr);
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = br.readLine()) != null) {
				sb.append(line).append("\n");
			}
			br.close();
			// Remove trailing newline if original file didn't end with one
			if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
				sb.setLength(sb.length() - 1);
			}
			return sb.toString();
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Writes a String to a file with explicit encoding.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var ok = system.writeStringToFile("/tmp/output.txt", content, "UTF-8", false);
	 * // Append to existing file:
	 * system.writeStringToFile("/tmp/log.txt", "new line\n", "UTF-8", true);
	 * </pre>
	 *
	 * @param filename
	 *            Path to the output file
	 * @param content
	 *            The string content to write
	 * @param encoding
	 *            Character encoding (e.g. "UTF-8"), or null for platform default
	 * @param append
	 *            If true, content is appended to an existing file rather than
	 *            overwriting it
	 * @return true if write succeeded, false on error
	 * @see #readFileToString(String, String)
	 * @since 10.1
	 */
	public boolean writeStringToFile(String filename, String content, String encoding, boolean append) {
		if (filename == null)
			return false;
		try {
			java.io.OutputStream os = new FileOutputStream(filename, append);
			OutputStreamWriter osw = (encoding != null)
					? new OutputStreamWriter(os, encoding)
					: new OutputStreamWriter(os);
			BufferedWriter bw = new BufferedWriter(osw);
			bw.write(content != null ? content : "");
			bw.close();
			return true;
		} catch (Exception e) {
			lastError = e;
			return false;
		}
	}

	/**
	 * Returns the size of a file in bytes as a string (safe for large files).
	 * <p>
	 * The size is returned as a String to avoid JavaScript precision loss for
	 * files larger than 2^53 bytes.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var size = system.getFileSize("/data/dump.bin");
	 * task.logmsg("File size: " + size + " bytes");
	 * </pre>
	 *
	 * @param filename
	 *            Path to the file
	 * @return File size in bytes as a String, or null if the file does not exist
	 * @since 10.1
	 */
	public String getFileSize(String filename) {
		if (filename == null)
			return null;
		File f = new File(filename);
		if (!f.exists() || !f.isFile())
			return null;
		return Long.toString(f.length());
	}

	/**
	 * Tests whether a file exists and is a regular file.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * if (system.fileExists("/etc/tdi/keystore.jks")) {
	 *     // load keystore
	 * }
	 * </pre>
	 *
	 * @param filename
	 *            Path to the file
	 * @return true if the path exists and is a regular file
	 * @see #directoryExists(String)
	 * @since 10.1
	 */
	public boolean fileExists(String filename) {
		if (filename == null)
			return false;
		File f = new File(filename);
		return f.exists() && f.isFile();
	}

	/**
	 * Tests whether a directory exists.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * if (!system.directoryExists("/output")) {
	 *     system.createDirectory("/output", true);
	 * }
	 * </pre>
	 *
	 * @param dirname
	 *            Path to the directory
	 * @return true if the path exists and is a directory
	 * @see #fileExists(String)
	 * @see #createDirectory(String, boolean)
	 * @since 10.1
	 */
	public boolean directoryExists(String dirname) {
		if (dirname == null)
			return false;
		File f = new File(dirname);
		return f.exists() && f.isDirectory();
	}

	/**
	 * Creates a directory, optionally creating all parent directories.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * system.createDirectory("/output/reports/2024", true);
	 * </pre>
	 *
	 * @param dirname
	 *            Path of the directory to create
	 * @param createParents
	 *            If true, any missing parent directories are also created
	 * @return true if the directory was created or already existed
	 * @see #directoryExists(String)
	 * @since 10.1
	 */
	public boolean createDirectory(String dirname, boolean createParents) {
		if (dirname == null)
			return false;
		File f = new File(dirname);
		if (f.exists())
			return f.isDirectory();
		return createParents ? f.mkdirs() : f.mkdir();
	}

	/**
	 * Lists the names of files and directories inside a directory.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var entries = system.listDirectory("/data/input");
	 * for (var i = 0; i &lt; entries.length; i++) {
	 *     task.logmsg(entries[i]);
	 * }
	 * </pre>
	 *
	 * @param dirname
	 *            Path to the directory to list
	 * @return Array of entry names (not full paths), or null if the directory
	 *         does not exist or an error occurs
	 * @since 10.1
	 */
	public String[] listDirectory(String dirname) {
		if (dirname == null)
			return null;
		File f = new File(dirname);
		if (!f.exists() || !f.isDirectory()) {
			lastError = new IllegalArgumentException("Not a directory: " + dirname);
			return null;
		}
		String[] list = f.list();
		return list != null ? list : new String[0];
	}

	/**
	 * Returns the last-modified timestamp of a file as a string.
	 * <p>
	 * The value is returned as milliseconds-since-epoch in string form to
	 * preserve full 64-bit precision in the JavaScript environment.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var ts = system.getFileModifiedTime("/data/import.csv");
	 * var date = system.timestampToDate(ts);
	 * </pre>
	 *
	 * @param filename
	 *            Path to the file
	 * @return Last-modified time in milliseconds since epoch as a String, or
	 *         null if the file does not exist
	 * @see #timestampToDate(String)
	 * @since 10.1
	 */
	public String getFileModifiedTime(String filename) {
		if (filename == null)
			return null;
		File f = new File(filename);
		if (!f.exists())
			return null;
		return Long.toString(f.lastModified());
	}

	// =========================================================================
	// Phase 4: JSON / XML Utility Methods
	// =========================================================================

	/**
	 * Parses a JSON string into a Jackson {@link JsonNode} object tree.
	 * <p>
	 * The returned {@code JsonNode} can be traversed using Jackson's API or
	 * passed to other JSON utility methods in this class.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var node = system.parseJSON('{"name":"Alice","age":30}');
	 * var name = node.get("name").asText();
	 * </pre>
	 *
	 * @param jsonString
	 *            Valid JSON string
	 * @return Root {@link JsonNode}, or null on parse error
	 * @see #toJSON(Object)
	 * @see #getJSONValue(String, String)
	 * @since 10.1
	 */
	public JsonNode parseJSON(String jsonString) {
		if (jsonString == null)
			return null;
		try {
			return new ObjectMapper().readTree(jsonString);
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Serialises a Java object (Map, List, POJO, etc.) to a JSON string.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var map = new java.util.HashMap();
	 * map.put("name", "Alice");
	 * map.put("age", 30);
	 * var json = system.toJSON(map);
	 * // Result: {"name":"Alice","age":30}
	 * </pre>
	 *
	 * @param obj
	 *            Object to serialise
	 * @return JSON string, or null on error
	 * @see #parseJSON(String)
	 * @since 10.1
	 */
	public String toJSON(Object obj) {
		if (obj == null)
			return "null";
		try {
			return new ObjectMapper().writeValueAsString(obj);
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Validates whether a string is well-formed JSON.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * if (!system.validateJSON(payload)) {
	 *     throw "Invalid JSON payload: " + system.lastError;
	 * }
	 * </pre>
	 *
	 * @param jsonString
	 *            String to validate
	 * @return true if the string is valid JSON, false otherwise
	 * @since 10.1
	 */
	public boolean validateJSON(String jsonString) {
		if (jsonString == null)
			return false;
		try {
			new ObjectMapper().readTree(jsonString);
			return true;
		} catch (Exception e) {
			lastError = e;
			return false;
		}
	}

	/**
	 * Extracts a value from a JSON string using a simple dot-separated path.
	 * <p>
	 * Supports nested objects (dot notation) and array indexing (bracket
	 * notation). Returns the value as a String.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var json = '{"user":{"name":"Alice","roles":["admin","user"]}}';
	 * var name = system.getJSONValue(json, "user.name");       // "Alice"
	 * var role = system.getJSONValue(json, "user.roles[0]");   // "admin"
	 * </pre>
	 *
	 * @param jsonString
	 *            Valid JSON string
	 * @param path
	 *            Dot-separated path with optional bracket array indices
	 * @return Value at the path as a String, or null if not found
	 * @see #parseJSON(String)
	 * @since 10.1
	 */
	public String getJSONValue(String jsonString, String path) {
		if (jsonString == null || path == null)
			return null;
		try {
			JsonNode node = new ObjectMapper().readTree(jsonString);
			String[] segments = path.split("\\.");
			for (String segment : segments) {
				if (node == null)
					return null;
				// Handle array indexing e.g. roles[0]
				Matcher m = Pattern.compile("^(.+?)\\[(\\d+)\\]$").matcher(segment);
				if (m.matches()) {
					node = node.path(m.group(1));
					node = node.path(Integer.parseInt(m.group(2)));
				} else {
					node = node.path(segment);
				}
			}
			if (node == null || node.isMissingNode())
				return null;
			return node.isTextual() ? node.asText() : node.toString();
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Returns a pretty-printed (indented) version of a JSON string.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var pretty = system.prettyPrintJSON('{"a":1,"b":2}');
	 * </pre>
	 *
	 * @param jsonString
	 *            Compact JSON string
	 * @return Indented JSON string, or null on parse error
	 * @since 10.1
	 */
	public String prettyPrintJSON(String jsonString) {
		if (jsonString == null)
			return null;
		try {
			ObjectMapper mapper = new ObjectMapper();
			Object obj = mapper.readValue(jsonString, Object.class);
			return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Returns a pretty-printed (indented) version of an XML string.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var pretty = system.prettyPrintXML("&lt;root&gt;&lt;a&gt;1&lt;/a&gt;&lt;/root&gt;");
	 * </pre>
	 *
	 * @param xmlString
	 *            Compact XML string
	 * @return Indented XML string, or null on parse error
	 * @since 10.1
	 */
	public String prettyPrintXML(String xmlString) {
		if (xmlString == null)
			return null;
		try {
			DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
			DocumentBuilder db = dbf.newDocumentBuilder();
			org.w3c.dom.Document doc = db.parse(
					new java.io.ByteArrayInputStream(xmlString.getBytes("UTF-8")));

			TransformerFactory tf = TransformerFactory.newInstance();
			Transformer transformer = tf.newTransformer();
			transformer.setOutputProperty(OutputKeys.INDENT, "yes");
			transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
			transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");

			StringWriter sw = new StringWriter();
			transformer.transform(new DOMSource(doc), new StreamResult(sw));
			return sw.toString();
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Validates an XML string for well-formedness.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * if (!system.validateXML(xmlPayload)) {
	 *     throw "Malformed XML: " + system.lastError;
	 * }
	 * </pre>
	 *
	 * @param xmlString
	 *            XML string to validate
	 * @return true if the string is well-formed XML, false otherwise
	 * @since 10.1
	 */
	public boolean validateXML(String xmlString) {
		if (xmlString == null)
			return false;
		try {
			DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
			DocumentBuilder db = dbf.newDocumentBuilder();
			db.parse(new java.io.ByteArrayInputStream(xmlString.getBytes("UTF-8")));
			return true;
		} catch (Exception e) {
			lastError = e;
			return false;
		}
	}

	/**
	 * Converts a simple XML document to a JSON string.
	 * <p>
	 * Element text content becomes a string value; attributes and nested
	 * elements become object members. Repeated sibling elements become JSON
	 * arrays.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var json = system.xmlToJSON("&lt;user&gt;&lt;name&gt;Alice&lt;/name&gt;&lt;/user&gt;");
	 * // Result: {"user":{"name":"Alice"}}
	 * </pre>
	 *
	 * @param xmlString
	 *            XML string to convert
	 * @return JSON string, or null on error
	 * @see #jsonToXML(String, String)
	 * @since 10.1
	 */
	public String xmlToJSON(String xmlString) {
		if (xmlString == null)
			return null;
		try {
			DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
			DocumentBuilder db = dbf.newDocumentBuilder();
			org.w3c.dom.Document doc = db.parse(
					new java.io.ByteArrayInputStream(xmlString.getBytes("UTF-8")));
			Map<String, Object> map = new LinkedHashMap<>();
			map.put(doc.getDocumentElement().getNodeName(),
					xmlNodeToMap(doc.getDocumentElement()));
			return new ObjectMapper().writeValueAsString(map);
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/** Recursively converts a DOM node to a Map/String for JSON serialisation. */
	@SuppressWarnings("unchecked")
	private Object xmlNodeToMap(org.w3c.dom.Node node) {
		org.w3c.dom.NodeList children = node.getChildNodes();
		boolean hasElementChildren = false;
		for (int i = 0; i < children.getLength(); i++) {
			if (children.item(i).getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
				hasElementChildren = true;
				break;
			}
		}
		if (!hasElementChildren) {
			return node.getTextContent();
		}
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < children.getLength(); i++) {
			org.w3c.dom.Node child = children.item(i);
			if (child.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE)
				continue;
			String name = child.getNodeName();
			Object value = xmlNodeToMap(child);
			if (map.containsKey(name)) {
				Object existing = map.get(name);
				if (existing instanceof List) {
					((List<Object>) existing).add(value);
				} else {
					List<Object> list = new ArrayList<>();
					list.add(existing);
					list.add(value);
					map.put(name, list);
				}
			} else {
				map.put(name, value);
			}
		}
		return map;
	}

	/**
	 * Converts a flat JSON object to an XML string with a specified root element.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var xml = system.jsonToXML('{"name":"Alice","dept":"IT"}', "user");
	 * // Result: &lt;user&gt;&lt;name&gt;Alice&lt;/name&gt;&lt;dept&gt;IT&lt;/dept&gt;&lt;/user&gt;
	 * </pre>
	 *
	 * @param jsonString
	 *            JSON string to convert (top-level must be an object)
	 * @param rootElement
	 *            Name for the XML root element
	 * @return XML string, or null on error
	 * @see #xmlToJSON(String)
	 * @since 10.1
	 */
	public String jsonToXML(String jsonString, String rootElement) {
		if (jsonString == null)
			return null;
		if (rootElement == null || rootElement.isEmpty())
			rootElement = "root";
		try {
			JsonNode node = new ObjectMapper().readTree(jsonString);
			StringBuilder sb = new StringBuilder("<").append(rootElement).append(">");
			jsonNodeToXML(node, sb);
			sb.append("</").append(rootElement).append(">");
			return sb.toString();
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/** Recursively serialises a JsonNode to XML fragment. */
	private void jsonNodeToXML(JsonNode node, StringBuilder sb) {
		if (node.isObject()) {
			Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> field = fields.next();
				String key = field.getKey();
				JsonNode val = field.getValue();
				sb.append("<").append(key).append(">");
				jsonNodeToXML(val, sb);
				sb.append("</").append(key).append(">");
			}
		} else if (node.isArray()) {
			for (JsonNode item : node) {
				jsonNodeToXML(item, sb);
			}
		} else {
			// Escape XML special characters in text
			String text = node.asText();
			sb.append(text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
		}
	}

	// =========================================================================
	// Phase 4: Collection Utility Methods
	// =========================================================================

	/**
	 * Filters a list of {@link Entry} objects, keeping only those where the
	 * named attribute equals the given value.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var admins = system.filterList(users, "department", "IT");
	 * </pre>
	 *
	 * @param list
	 *            List of Entry objects to filter
	 * @param attribute
	 *            Attribute name to test
	 * @param value
	 *            Value to match (uses {@code toString()} comparison)
	 * @return New list containing only matching entries
	 * @see #findInList(List, String, Object)
	 * @since 10.1
	 */
	public List<Entry> filterList(List<Entry> list, String attribute, Object value) {
		List<Entry> result = new ArrayList<>();
		if (list == null || attribute == null)
			return result;
		String matchVal = (value != null) ? value.toString() : null;
		for (Entry e : list) {
			try {
				Object v = e.getAttribute(attribute);
				if (v == null && matchVal == null) {
					result.add(e);
				} else if (v != null && v.toString().equals(matchVal)) {
					result.add(e);
				}
			} catch (Exception ex) {
				// skip entry on access error
			}
		}
		return result;
	}

	/**
	 * Sorts a list of {@link Entry} objects by the named attribute.
	 * <p>
	 * The sort is performed using lexicographic ordering on the attribute's
	 * string value. Null attribute values are sorted to the end.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var sorted = system.sortList(users, "sn", true);  // ascending by surname
	 * </pre>
	 *
	 * @param list
	 *            List of Entry objects to sort (sorted in-place and returned)
	 * @param attribute
	 *            Attribute name to sort by
	 * @param ascending
	 *            true for ascending, false for descending
	 * @return Sorted list (same list instance)
	 * @since 10.1
	 */
	public List<Entry> sortList(List<Entry> list, final String attribute, final boolean ascending) {
		if (list == null || attribute == null)
			return list;
		Collections.sort(list, new Comparator<Entry>() {
			public int compare(Entry a, Entry b) {
				try {
					Object va = a.getAttribute(attribute);
					Object vb = b.getAttribute(attribute);
					if (va == null && vb == null) return 0;
					if (va == null) return ascending ? 1 : -1;
					if (vb == null) return ascending ? -1 : 1;
					int cmp = va.toString().compareTo(vb.toString());
					return ascending ? cmp : -cmp;
				} catch (Exception e) {
					return 0;
				}
			}
		});
		return list;
	}

	/**
	 * Extracts the values of a named attribute from every entry in a list.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var emails = system.mapList(users, "mail");
	 * // Result: ["alice@example.com", "bob@example.com", ...]
	 * </pre>
	 *
	 * @param list
	 *            List of Entry objects
	 * @param attribute
	 *            Attribute name to extract from each entry
	 * @return List of string values (null attribute values are included as null)
	 * @since 10.1
	 */
	public List<String> mapList(List<Entry> list, String attribute) {
		List<String> result = new ArrayList<>();
		if (list == null || attribute == null)
			return result;
		for (Entry e : list) {
			try {
				Object v = e.getAttribute(attribute);
				result.add(v != null ? v.toString() : null);
			} catch (Exception ex) {
				result.add(null);
			}
		}
		return result;
	}

	/**
	 * Finds the first {@link Entry} in a list where the named attribute equals
	 * the given value.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var alice = system.findInList(users, "uid", "alice");
	 * if (alice != null) { ... }
	 * </pre>
	 *
	 * @param list
	 *            List of Entry objects to search
	 * @param attribute
	 *            Attribute name to test
	 * @param value
	 *            Value to match
	 * @return First matching Entry, or null if not found
	 * @see #filterList(List, String, Object)
	 * @since 10.1
	 */
	public Entry findInList(List<Entry> list, String attribute, Object value) {
		if (list == null || attribute == null)
			return null;
		String matchVal = (value != null) ? value.toString() : null;
		for (Entry e : list) {
			try {
				Object v = e.getAttribute(attribute);
				if (v == null && matchVal == null) return e;
				if (v != null && v.toString().equals(matchVal)) return e;
			} catch (Exception ex) {
				// skip
			}
		}
		return null;
	}

	/**
	 * Groups a list of {@link Entry} objects by the values of a named attribute.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var byDept = system.groupBy(users, "department");
	 * var itUsers = byDept.get("IT");
	 * </pre>
	 *
	 * @param list
	 *            List of Entry objects to group
	 * @param attribute
	 *            Attribute name to group by
	 * @return Map from attribute value (String) to List of matching entries
	 * @since 10.1
	 */
	public Map<String, List<Entry>> groupBy(List<Entry> list, String attribute) {
		Map<String, List<Entry>> result = new LinkedHashMap<>();
		if (list == null || attribute == null)
			return result;
		for (Entry e : list) {
			try {
				Object v = e.getAttribute(attribute);
				String key = (v != null) ? v.toString() : "__null__";
				if (!result.containsKey(key)) {
					result.put(key, new ArrayList<Entry>());
				}
				result.get(key).add(e);
			} catch (Exception ex) {
				// skip
			}
		}
		return result;
	}

	/**
	 * Returns a list with duplicate entries removed, based on the value of a
	 * named attribute.
	 * <p>
	 * The first occurrence of each attribute value is retained; subsequent
	 * duplicates are discarded.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var unique = system.distinctList(entries, "mail");
	 * </pre>
	 *
	 * @param list
	 *            List of Entry objects
	 * @param attribute
	 *            Attribute name used as the uniqueness key
	 * @return New list with duplicates removed
	 * @since 10.1
	 */
	public List<Entry> distinctList(List<Entry> list, String attribute) {
		List<Entry> result = new ArrayList<>();
		java.util.Set<String> seen = new HashSet<>();
		if (list == null || attribute == null)
			return result;
		for (Entry e : list) {
			try {
				Object v = e.getAttribute(attribute);
				String key = (v != null) ? v.toString() : "__null__";
				if (seen.add(key)) {
					result.add(e);
				}
			} catch (Exception ex) {
				result.add(e);
			}
		}
		return result;
	}

	/**
	 * Returns a reversed copy of a list.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var reversed = system.reverseList(entries);
	 * </pre>
	 *
	 * @param list
	 *            List to reverse
	 * @return New list in reverse order
	 * @since 10.1
	 */
	public <T> List<T> reverseList(List<T> list) {
		List<T> result = new ArrayList<>();
		if (list == null)
			return result;
		for (int i = list.size() - 1; i >= 0; i--) {
			result.add(list.get(i));
		}
		return result;
	}

	/**
	 * Returns a sub-list (slice) of a list between two indices.
	 * <p>
	 * Indices are zero-based and clamped to list bounds. The {@code end} index
	 * is exclusive (consistent with {@link List#subList}).
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var page = system.sliceList(entries, 0, 10);   // first 10
	 * var next = system.sliceList(entries, 10, 20);  // items 10-19
	 * </pre>
	 *
	 * @param list
	 *            Source list
	 * @param start
	 *            Start index (inclusive, zero-based)
	 * @param end
	 *            End index (exclusive); use list.size() for "to the end"
	 * @return Sub-list view
	 * @since 10.1
	 */
	public <T> List<T> sliceList(List<T> list, int start, int end) {
		if (list == null)
			return new ArrayList<T>();
		int s = Math.max(0, start);
		int e = Math.min(list.size(), end);
		if (s >= e)
			return new ArrayList<T>();
		return new ArrayList<T>(list.subList(s, e));
	}

	// =========================================================================
	// Phase 4: Encoding / Decoding Methods
	// =========================================================================

	/**
	 * URL-encodes a string using the specified character encoding.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var encoded = system.urlEncode("hello world &amp; more", "UTF-8");
	 * // Result: "hello+world+%26+more"
	 * </pre>
	 *
	 * @param str
	 *            The string to encode
	 * @param encoding
	 *            Character encoding (e.g. "UTF-8"), or null for UTF-8
	 * @return URL-encoded string, or null on error
	 * @see #urlDecode(String, String)
	 * @since 10.1
	 */
	public String urlEncode(String str, String encoding) {
		if (str == null)
			return null;
		try {
			String enc = (encoding != null && !encoding.isEmpty()) ? encoding : "UTF-8";
			return java.net.URLEncoder.encode(str, enc);
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * URL-decodes a string using the specified character encoding.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var decoded = system.urlDecode("hello+world+%26+more", "UTF-8");
	 * // Result: "hello world &amp; more"
	 * </pre>
	 *
	 * @param str
	 *            The URL-encoded string to decode
	 * @param encoding
	 *            Character encoding (e.g. "UTF-8"), or null for UTF-8
	 * @return Decoded string, or null on error
	 * @see #urlEncode(String, String)
	 * @since 10.1
	 */
	public String urlDecode(String str, String encoding) {
		if (str == null)
			return null;
		try {
			String enc = (encoding != null && !encoding.isEmpty()) ? encoding : "UTF-8";
			return java.net.URLDecoder.decode(str, enc);
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * HTML-encodes a string, converting special characters to HTML entities.
	 * <p>
	 * Encodes {@code &}, {@code <}, {@code >}, {@code "} and {@code '}.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var safe = system.htmlEncode("&lt;script&gt;alert('xss')&lt;/script&gt;");
	 * // Result: "&amp;lt;script&amp;gt;alert(&amp;#39;xss&amp;#39;)&amp;lt;/script&amp;gt;"
	 * </pre>
	 *
	 * @param str
	 *            String to encode
	 * @return HTML-encoded string
	 * @see #htmlDecode(String)
	 * @since 10.1
	 */
	public String htmlEncode(String str) {
		if (str == null)
			return null;
		return str.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;")
				.replace("\"", "&quot;")
				.replace("'", "&#39;");
	}

	/**
	 * HTML-decodes a string, converting HTML entities back to characters.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var text = system.htmlDecode("Hello &amp;amp; World");
	 * // Result: "Hello &amp; World"
	 * </pre>
	 *
	 * @param str
	 *            HTML-encoded string
	 * @return Decoded string
	 * @see #htmlEncode(String)
	 * @since 10.1
	 */
	public String htmlDecode(String str) {
		if (str == null)
			return null;
		return str.replace("&amp;", "&")
				.replace("&lt;", "<")
				.replace("&gt;", ">")
				.replace("&quot;", "\"")
				.replace("&#39;", "'");
	}

	/**
	 * Base64-encodes a string with optional URL-safe alphabet.
	 * <p>
	 * Standard Base64 uses {@code +} and {@code /}; URL-safe Base64 replaces
	 * these with {@code -} and {@code _} (RFC 4648 §5).
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var encoded = system.base64Encode("Hello World", "UTF-8", false);
	 * var urlSafe = system.base64Encode("Hello World", "UTF-8", true);
	 * </pre>
	 *
	 * @param string
	 *            String to encode
	 * @param encoding
	 *            Source character encoding (e.g. "UTF-8")
	 * @param urlSafe
	 *            If true, use URL-safe alphabet (no padding, {@code -} and {@code _})
	 * @return Base64-encoded string, or null on error
	 * @see #base64Decode(String, String, boolean)
	 * @since 10.1
	 */
	public String base64Encode(String string, String encoding, boolean urlSafe) {
		if (string == null)
			return null;
		try {
			String enc = (encoding != null && !encoding.isEmpty()) ? encoding : "UTF-8";
			byte[] bytes = string.getBytes(enc);
			if (urlSafe) {
				return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
			} else {
				return java.util.Base64.getEncoder().encodeToString(bytes);
			}
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	/**
	 * Base64-decodes a string with optional URL-safe alphabet support.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var decoded = system.base64Decode("SGVsbG8gV29ybGQ=", "UTF-8", false);
	 * // Result: "Hello World"
	 * </pre>
	 *
	 * @param str
	 *            Base64-encoded string
	 * @param encoding
	 *            Target character encoding for the decoded bytes (e.g. "UTF-8")
	 * @param urlSafe
	 *            If true, use URL-safe alphabet decoder
	 * @return Decoded string, or null on error
	 * @see #base64Encode(String, String, boolean)
	 * @since 10.1
	 */
	public String base64Decode(String str, String encoding, boolean urlSafe) {
		if (str == null)
			return null;
		try {
			String enc = (encoding != null && !encoding.isEmpty()) ? encoding : "UTF-8";
			byte[] bytes;
			if (urlSafe) {
				bytes = java.util.Base64.getUrlDecoder().decode(str);
			} else {
				bytes = java.util.Base64.getDecoder().decode(str);
			}
			return new String(bytes, enc);
		} catch (Exception e) {
			lastError = e;
			return null;
		}
	}

	// =========================================================================
	// Phase 4: Validation and Security Methods
	// =========================================================================

	/**
	 * Validates an email address format using RFC 5322-inspired pattern.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * if (!system.validateEmail(work.getString("mail"))) {
	 *     throw "Invalid email: " + work.getString("mail");
	 * }
	 * </pre>
	 *
	 * @param email
	 *            Email address to validate
	 * @return true if the format is valid
	 * @since 10.1
	 */
	public boolean validateEmail(String email) {
		if (email == null || email.trim().isEmpty())
			return false;
		Pattern p = Pattern.compile(
				"^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$");
		return p.matcher(email.trim()).matches();
	}

	/**
	 * Validates a URL string (http or https).
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * if (system.validateURL(endpoint)) {
	 *     // safe to use
	 * }
	 * </pre>
	 *
	 * @param url
	 *            URL string to validate
	 * @return true if the URL has a valid http/https format
	 * @since 10.1
	 */
	public boolean validateURL(String url) {
		if (url == null || url.trim().isEmpty())
			return false;
		try {
			java.net.URL u = new java.net.URL(url.trim());
			String proto = u.getProtocol();
			return "http".equalsIgnoreCase(proto) || "https".equalsIgnoreCase(proto);
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Validates an IPv4 or IPv6 address string.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * if (system.validateIPAddress("192.168.1.1")) { ... }
	 * if (system.validateIPAddress("::1")) { ... }
	 * </pre>
	 *
	 * @param ip
	 *            IP address string to validate
	 * @return true if the string is a valid IPv4 or IPv6 address
	 * @since 10.1
	 */
	public boolean validateIPAddress(String ip) {
		if (ip == null || ip.trim().isEmpty())
			return false;
		try {
			java.net.InetAddress.getByName(ip.trim());
			// InetAddress.getByName also does DNS lookup for hostnames;
			// use pattern check to restrict to numeric addresses only
			Pattern ipv4 = Pattern.compile(
					"^(\\d{1,3}\\.){3}\\d{1,3}$");
			Pattern ipv6 = Pattern.compile(
					"^([0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}$");
			String trimmed = ip.trim();
			return ipv4.matcher(trimmed).matches() || ipv6.matcher(trimmed).matches();
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Validates a password against configurable complexity rules.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * if (!system.validatePassword(newPassword, 8, true)) {
	 *     throw "Password must be 8+ chars and include a special character";
	 * }
	 * </pre>
	 *
	 * @param password
	 *            Password string to validate
	 * @param minLength
	 *            Minimum required length
	 * @param requireSpecial
	 *            If true, at least one special character ({@code !@#$%^&*}) is required
	 * @return true if the password meets all requirements
	 * @since 10.1
	 */
	public boolean validatePassword(String password, int minLength, boolean requireSpecial) {
		if (password == null)
			return false;
		if (password.length() < minLength)
			return false;
		if (requireSpecial) {
			Pattern special = Pattern.compile("[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?]");
			if (!special.matcher(password).find())
				return false;
		}
		return true;
	}

	/**
	 * Sanitises an HTML string by stripping potentially dangerous tags and
	 * attributes to prevent XSS attacks.
	 * <p>
	 * Removes {@code <script>}, {@code <iframe>}, {@code <object>},
	 * {@code <embed>}, and inline event handler attributes
	 * ({@code on*="..."}).
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var safe = system.sanitizeHTML(userInput);
	 * </pre>
	 *
	 * @param html
	 *            HTML string to sanitise
	 * @return Sanitised HTML string
	 * @since 10.1
	 */
	public String sanitizeHTML(String html) {
		if (html == null)
			return null;
		String result = html;
		// Remove dangerous tags (case-insensitive, including attributes)
		result = result.replaceAll("(?i)<script[^>]*>[\\s\\S]*?</script>", "");
		result = result.replaceAll("(?i)<iframe[^>]*>[\\s\\S]*?</iframe>", "");
		result = result.replaceAll("(?i)<object[^>]*>[\\s\\S]*?</object>", "");
		result = result.replaceAll("(?i)<embed[^>]*>", "");
		// Remove inline event handlers
		result = result.replaceAll("(?i)\\s+on\\w+\\s*=\\s*\"[^\"]*\"", "");
		result = result.replaceAll("(?i)\\s+on\\w+\\s*=\\s*'[^']*'", "");
		return result;
	}

	/**
	 * Escapes a string for safe use in a SQL query, preventing SQL injection.
	 * <p>
	 * Escapes single quotes by doubling them. For production use, always prefer
	 * parameterised queries; this method is a last-resort fallback.
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var safe = system.escapeSQL(userInput);
	 * var sql = "SELECT * FROM users WHERE cn = '" + safe + "'";
	 * </pre>
	 *
	 * @param input
	 *            String to escape
	 * @return SQL-escaped string
	 * @since 10.1
	 */
	public String escapeSQL(String input) {
		if (input == null)
			return null;
		return input.replace("'", "''").replace("\\", "\\\\");
	}

	/**
	 * Sanitises a filename by removing or replacing characters that are illegal
	 * on common file systems (Windows, Linux, macOS).
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var safe = system.sanitizeFilename("report: Jan/2024.xlsx");
	 * // Result: "report_ Jan_2024.xlsx"
	 * </pre>
	 *
	 * @param filename
	 *            Filename (not a path) to sanitise
	 * @return Sanitised filename safe for all major operating systems
	 * @since 10.1
	 */
	public String sanitizeFilename(String filename) {
		if (filename == null)
			return null;
		// Replace characters illegal on Windows/Linux/macOS
		String safe = filename.replaceAll("[\\\\/:*?\"<>|]", "_");
		// Remove control characters
		safe = safe.replaceAll("[\\x00-\\x1f\\x7f]", "");
		// Trim leading/trailing dots and spaces (Windows restriction)
		safe = safe.replaceAll("^[. ]+|[. ]+$", "");
		return safe.isEmpty() ? "_" : safe;
	}

	/**
	 * Generates a random UUID (Universally Unique Identifier) as a string.
	 * <p>
	 * The returned string uses the standard 8-4-4-4-12 hyphenated format
	 * (UUID version 4, randomly generated).
	 * <p>
	 * <b>Example:</b>
	 *
	 * <pre>
	 * var id = system.generateUUID();
	 * work.put("requestId", id);
	 * </pre>
	 *
	 * @return UUID string in the form {@code xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx}
	 * @since 10.1
	 */
	public String generateUUID() {
		return UUID.randomUUID().toString();
	}


}


/** Internal error listener implementation for XML transformations. */
class ErrorListenerImpl implements javax.xml.transform.ErrorListener {
	/** The captured exception. */
	public java.lang.Exception excep = null;

	/** Creates a new ErrorListenerImpl. */
	ErrorListenerImpl() {
		excep = null;
	}

	/**
	 * Receives notification of a recoverable error. Ignored — the transformer
	 * continues processing.
	 *
	 * @param e the transformer exception describing the warning
	 */
	public void warning(javax.xml.transform.TransformerException e) {
		// Do Nothing.

	}

	/**
	 * Receives notification of a recoverable error. Stores the exception so the
	 * caller can inspect it after the transformation.
	 *
	 * @param e the transformer exception describing the error
	 */
	public void error(javax.xml.transform.TransformerException e) {
		this.excep = e;
	}

	/**
	 * Receives notification of a non-recoverable error. Stores the exception so
	 * the caller can inspect it after the transformation.
	 *
	 * @param e the transformer exception describing the fatal error
	 */
	public void fatalError(javax.xml.transform.TransformerException e) {
		this.excep = e;
	}
}

