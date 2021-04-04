import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.sql.Statement;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.w3c.dom.Node;
import org.w3c.dom.NamedNodeMap;

//
// RssHowler
//
// A simple database driven podcast downloader
//
// Copyright 2021 Jeff Anton
// See LICENSE file
// Check github.com for JeffAnton/RssHowler

// Usage:

// arguments are either a jdbc connection string or an rss url

// a jdbc connection string will read feeds from the feed table
// process them and download items as needed

// an rss url will just read the feed and try to parse it

// Schema setup...
// create table podcasts (guid text primary key, url text, title text, feed text);
// create table feeds (rssurl text primary key,
//			last bigint default 0 not null,
//			flags smallint default 0 not null);

// feed table flags
//
// 0 (no bits) - do nothing with this feed - just remember it
// 1 - download feed
// 2 - update podcasts table with feed items
// 3 (1 & 2) - normal operation - download feed and items
// 4 - special name handling - make all file names unique - needed for
//	feeds with poor filename generation
// 7 (1 & 2 & 4) normal download with filename correction
// 8 - do not download feeds but do update podcasts table - to skip old items
// 11 (1 & 2 & 8) update podcasts table without downloading

// adding a feed example
// sql insert
// insert into feeds (rssurl, flags) values ('https://<feed>', 3);

class rsshowler {

    static DocumentBuilderFactory factory;
    static Connection dbconn;
    static String useragent = "RssHowler/0.9";

    public static void
    main(String argv[]) {
	factory = DocumentBuilderFactory.newInstance();
	dbconn = null;
	try {
	    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
	    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
	    factory.setXIncludeAware(false);
	    factory.setExpandEntityReferences(false);

	    for (String s : argv)
		doarg(s);
	} catch (ParserConfigurationException e) {
	    System.out.println("parser configuration problems");
	}
    }

    static void
    workfeed(Element e, int flags) {
	/*
	  follow rss/channel/
	  get feed
	  foreach item
	  title value
	  guid value
	  enclosure attr url
	*/
	reporttag(e, "lastBuildDate");
	reporttag(e, "ttl");
	reporttag(e, "skipDays");
	reporttag(e, "skipHours");
	NodeList tlist = e.getElementsByTagName("title");
	String feed = tlist.item(0).getTextContent();
	NodeList items = e.getElementsByTagName("item");
	for (int i = 0; i < items.getLength(); ++i) {
	    Node item = items.item(i);
	    NodeList il = item.getChildNodes();
	    String guid, url, title;
	    guid = null;
	    url = null;
	    title = null;
	    for (int j = 0; j < il.getLength(); ++j) {
		Node f = il.item(j);
		String nn = f.getNodeName();
		if (nn.equals("guid")) {
		    guid = f.getTextContent();
		} else if (nn.equals("enclosure")) {
		    NamedNodeMap n = f.getAttributes();
		    Node u = n.getNamedItem("url");
		    url = u.getNodeValue();
		} else if (nn.equals("title")) {
		    title = f.getTextContent();
		}
	    }
	    if (guid != null && url != null && title != null)
		if (dbconn == null) {
		    System.out.println(title + ":guid=" + guid + ":url=" + url + ":feed=" + feed);
		} else {
		    if ((flags & 2) == 2 &&
			    addpodcast(guid, url, title, feed) == 1)
			dosave(url, feed, flags);
		}
	}
    }

    static void
    reporttag(Element e, String tag) {
	NodeList list = e.getElementsByTagName(tag);
	for (int i = 0; i < list.getLength(); ++i)
	    System.out.println(tag + i + " is " + list.item(i).getTextContent());
    }

    static int
    addpodcast(String guid, String url, String title, String feed) {
	try {
	    PreparedStatement st = dbconn.prepareStatement(
		"insert into podcasts values (?,?,?,?)");
	    st.setString(1, guid);
	    st.setString(2, url);
	    st.setString(3, title);
	    st.setString(4, feed);
	    int r = st.executeUpdate();
	    System.out.println(r + " row updated");
	    st.close();
	    if (r > 0) {
		System.out.println(title + ":guid=" + guid + ":url=" + url + ":feed=" + feed);
		return 1;
	    }
	} catch (SQLException e) {
	}
	return 0;
    }

    static int
    checkpodcast(String guid) {
	int c = 0;
	try {
	    PreparedStatement st = dbconn.prepareStatement(
		"select count(*) from podcasts where guid = ?");
	    st.setString(1, guid);
	    ResultSet r = st.executeQuery();
	    while (r.next())
		c = r.getInt(1);
	    st.close();
	} catch (SQLException e) {
	    System.out.println("check count failed");
	}
	return c;
    }

    static void
    dosave(String url, String feed, int flags) {
	// 8 flag means do not save
	if ((flags & 8) == 8)
	    return;
	int q = url.indexOf('?');
	String f = url;
	if (q == -1) {
	    int s = url.lastIndexOf('/');
	    f = url.substring(s+1);
	} else {
	    int s = url.lastIndexOf('/', q);
	    f = url.substring(s+1, q);
	}
	File d = new File(feed);
	d.mkdir();
	File p = new File(d, f);
	if ((flags & 4) == 4 || p.exists()) {
	    // need to choose a different name
	    f = System.currentTimeMillis() + f;
	    p = new File(d, f);
	}
	System.out.println("file " + p.getPath());
	try {
	    URLConnection uc = new URL(url).openConnection();
	    uc.setAllowUserInteraction(false);
	    uc.setRequestProperty("User-Agent", useragent);
	    uc.connect();
	    InputStream i = uc.getInputStream();
	    OutputStream o = new FileOutputStream(p);
	    byte[] b = new byte[20480];
	    int br;
	    while ((br = i.read(b)) > 0)
		o.write(b, 0, br);
	    i.close();
	    o.close();
	} catch (Exception e) {
	    e.printStackTrace();
	}
    }

    static void
    updatelast(String url, long t) {
	try {
	    PreparedStatement st = dbconn.prepareStatement(
		"update feeds set last = ? where rssurl = ?");
	    st.setLong(1, t);
	    st.setString(2, url);
	    int r = st.executeUpdate();
	    System.out.println("updated feed " + url + " at time " + t);
	} catch (SQLException e) {
	    System.out.println("update feed fail " + e.getMessage());
	}
    }

    static void
    doarg(String arg) {
	if (arg.startsWith("jdbc")) {
	    // setup database
	    try {
		dbconn = DriverManager.getConnection(arg);
		// look for feeds and run them...
		Statement st = dbconn.createStatement();
		ResultSet r = st.executeQuery(
	"select rssurl, last, flags from feeds where flags > 0 order by 1");
		while (r.next()) {
		    long now = System.currentTimeMillis();
		    String url = r.getString(1);
		    if (dofetch(url, r.getLong(2), r.getShort(3)) == 0)
			updatelast(url, now);
		}
		st.close();
	    } catch (SQLException e) {
		System.out.println("Database connection problems " + e.getMessage());
	    }
	} else {
	    dofetch(arg, 0, 1);
	}
    }

    static int
    dofetch(String arg, long t, int flags) {
	System.out.println("dofetch " + arg);
	int ret = 1;
	try {
	    DocumentBuilder builder = factory.newDocumentBuilder();
	    URLConnection uc = new URL(arg).openConnection();
	    if (t > 0)
		uc.setIfModifiedSince(t);
	    uc.setAllowUserInteraction(false);
	    uc.setRequestProperty("User-Agent", useragent);
	    uc.connect();
	    String status = uc.getHeaderField(null);
	    if (!status.contains(" 304 ")) {
		if (!status.contains(" 200 "))
		    System.out.println("Status: " + status);
		Element doc =
		    builder.parse(uc.getInputStream()).getDocumentElement();
		workfeed(doc, flags);
		ret = 0;
	    }
	    String l = uc.getHeaderField("Last-Modified");
	    if (l != null)
		System.out.println("Last mod " + l);
	    l = uc.getHeaderField("Expires");
	    if (l != null)
		System.out.println("Expires " + l);
	    l = uc.getHeaderField("ETag");
	    if (l != null)
		System.out.println("ETag " + l);
	} catch (MalformedURLException e) {
	    System.out.println("Bad URL Form:" + arg);
	} catch (IOException i) {
	    System.out.println("I/O failure:" + arg);
	} catch (Exception ex) {
	    ex.printStackTrace();
	}
	return ret;
    }
}