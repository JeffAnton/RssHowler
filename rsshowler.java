import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.HttpURLConnection;
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
// Copyright 2022 Jeff Anton
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
//			flags smallint default 0 not null,
//			etag text);

// feed table flags
//
// negative values ignored
// 0 (no bits) - do nothing with this feed - just remember it
// 1 - download feed
// 2 - update podcasts table with feed items
// 3 (1 & 2) - normal operation - download feed and items
// 4 - special name handling - make all file names unique - needed for
//	feeds with poor filename generation
// 7 (1 & 2 & 4) normal download with filename correction
// 8 - do not download feeds but do update podcasts table - to skip old items
// 11 (1 & 2 & 8) update podcasts table without downloading
// 16 - HEAD operation only to check for working URLs
// 32 - Always fetch feed

// adding a feed example
// sql insert
// insert into feeds (rssurl, flags) values ('https://<feed>', 3);

class rsshowler {

    static DocumentBuilderFactory factory;
    static Connection dbconn;
    static String useragent = "RssHowler/1.7";

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
	String feed = tlist.item(0).getTextContent().trim();
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
		    guid = f.getTextContent().trim();
		} else if (nn.equals("enclosure")) {
		    NamedNodeMap n = f.getAttributes();
		    Node u = n.getNamedItem("url");
		    url = u.getNodeValue();
		} else if (nn.equals("title")) {
		    title = f.getTextContent().trim();
		}
	    }
	    if (guid != null && url != null && title != null)
		if (dbconn == null) {
		    System.out.println(title + ":guid=" + guid + ":url=" + url + ":feed=" + feed);
		} else {
		    if ((flags & 2) == 2 && checkpodcast(guid) == 0 &&
			    dosave(url, feed, flags))
			addpodcast(guid, url, title, feed);
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

    static boolean
    dosave(String url, String feed, int flags) {
	// 8 flag means do not save
	if ((flags & 8) == 8)
	    return true;	// true because we're accepting
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
	if ((flags & 16) != 16)
	    d.mkdir();
	File p = new File(d, f);
	if ((flags & 4) == 4 || p.exists()) {
	    // need to choose a different name
	    f = System.currentTimeMillis() + f;
	    p = new File(d, f);
	}
	System.out.println("file " + p.getPath());
	try {
	    HttpURLConnection uc =
		(HttpURLConnection)new URL(url).openConnection();
	    uc.setAllowUserInteraction(false);
	    if ((flags & 16) == 16)
		uc.setRequestMethod("HEAD");
	    uc.setRequestProperty("User-Agent", useragent);
	    uc.connect();
	    int status = uc.getResponseCode();
	    if (status != 200) {
		System.out.println("Unexpected Item Status: " + status);
		String loc = uc.getHeaderField("Location");
		if (loc != null) {
		    System.out.println("Location: " + loc);
		    if (status == 301 && loc.equals(url) == false) {
			// guess we have to do this ourselves...
			uc.disconnect();
			uc = (HttpURLConnection)new URL(loc).openConnection();
			uc.setAllowUserInteraction(false);
			if ((flags & 16) == 16)
			    uc.setRequestMethod("HEAD");
			uc.setRequestProperty("User-Agent", useragent);
			uc.connect();
			status = uc.getResponseCode();
			if (status != 200) {
			    System.out.println("Second Unexpected Item Status: " + status);
			    loc = uc.getHeaderField("Location");
			    if (loc != null)
				System.out.println("Location: " + loc);
			}
		    }
		}
	    }
	    InputStream i = uc.getInputStream();
	    if ((flags & 16) == 0) {
		OutputStream o = new FileOutputStream(p);
		byte[] b = new byte[20480];
		int br;
		while ((br = i.read(b)) > 0)
		    o.write(b, 0, br);
		o.close();
	    }
	    i.close();
	    return true;
	} catch (Exception e) {
	    e.printStackTrace();
	}
	return false;
    }

    static void
    updatelast(String url, long t, String etag) {
	try {
	    String up = "update feeds set last = ? where rssurl = ?";
	    if (etag != null)
		up = "update feeds set last = ?, etag = ? where rssurl = ?";
	    PreparedStatement st = dbconn.prepareStatement(up);
	    st.setLong(1, t);
	    if (etag != null) {
		st.setString(2, etag);
		st.setString(3, url);
	    } else {		
		st.setString(2, url);
	    }
	    int r = st.executeUpdate();
	    st.close();
	    System.out.println("updated feed " + url + " at time " + t);
	} catch (SQLException e) {
	    System.out.println("update feed fail " + e.getMessage());
	}
    }

    static void
    deadfeed(String url) {
	try {
	    String up = "update feeds set flags = 0 where rssurl = ?";
	    PreparedStatement st = dbconn.prepareStatement(up);
	    st.setString(1, url);
	    int r = st.executeUpdate();
	    st.close();
	    System.out.println("dead feed " + url);
	} catch (SQLException e) {
	    System.out.println("dead feed fail " + e.getMessage());
	}
    }

    static void
    movefeed(String url, String newurl) {
	try {
	    String up = "update feeds set rssurl = ? where rssurl = ?";
	    PreparedStatement st = dbconn.prepareStatement(up);
	    st.setString(1, newurl);
	    st.setString(2, url);
	    int r = st.executeUpdate();
	    st.close();
	    System.out.println("move feed " + url + " to " + newurl);
	} catch (SQLException e) {
	    System.out.println("move feed fail " + e.getMessage());
	}
    }

    static void
    doarg(String arg) {
	if (arg.startsWith("jdbc")) {
	    // setup database
	    try {
		dbconn = DriverManager.getConnection(arg);
		dbconn.setAutoCommit(true);
		// look for feeds and run them...
		Statement st = dbconn.createStatement();
		ResultSet r = st.executeQuery(
    "select rssurl, last, flags, etag from feeds where flags > 0 order by 1");
		while (r.next()) {
		    long now = System.currentTimeMillis();
		    dofetch(r.getString(1), r.getLong(2), r.getShort(3),
			now, r.getString(4));
		}
		st.close();
	    } catch (SQLException e) {
		System.out.println("Database connection problems " + e.getMessage());
	    }
	} else {
	    dofetch(arg, 0, 1, 0, null);
	}
    }

    static int
    dofetch(String arg, long t, int flags, long n, String etag) {
	System.out.println("dofetch " + arg);
	int ret = 1;
	try {
	    DocumentBuilder builder = factory.newDocumentBuilder();
	    HttpURLConnection uc =
		(HttpURLConnection)new URL(arg).openConnection();
	    uc.setInstanceFollowRedirects(false);
	    if ((flags & 32) != 32) {
		if (etag != null) {
		    uc.setRequestProperty("If-None-Match", etag);
		} else if (t > 0) {
		    uc.setIfModifiedSince(t);
		}
	    }
	    uc.setRequestProperty("User-Agent", useragent);
	    uc.setAllowUserInteraction(false);
	    uc.connect();
	    int status = uc.getResponseCode();
	    if (status == 200) {
		Element doc =
		    builder.parse(uc.getInputStream()).getDocumentElement();
		workfeed(doc, flags);
		ret = 0;
	    } else if (status == 410 || status == 404) {
		// feed is dead... clear flags
		System.out.println("Status: " + status + " FEED IS DEAD");
		deadfeed(arg);
	    } else {
		System.out.println("Status: " + status);
		String loc = uc.getHeaderField("Location");
		if (loc != null && loc.equals(arg) == false) {
		    System.out.println("Location: " + loc);
		    if (status >= 301 && status <= 309) {
			// feed is moved...
			System.out.println("FEED MOVED");
			movefeed(arg, loc);
			return dofetch(loc, t, flags, n, etag);
		    }
		}
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
	    if (ret == 0 && n > 0)
		updatelast(arg, n, l);
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
