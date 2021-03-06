package water.persist;

import java.io.*;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.concurrent.Callable;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.io.ByteStreams;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.*;

import water.*;

import water.api.HDFSIOException;
import water.api.Schema;
import water.api.TypeaheadV2;
import water.fvec.HDFSFileVec;
import water.fvec.Vec;
import water.util.FileUtils;
import water.util.Log;

/**
 * HDFS persistence layer.
 */
public final class PersistHdfs extends Persist {
  /** Globally shared HDFS configuration. */
  public static final Configuration CONF;
  /** Root path of HDFS */
  private final Path _iceRoot;

  // Returns String with path for given key.
  private static String getPathForKey(Key k) {
    final int off = k._kb[0]==Key.CHK ? Vec.KEY_PREFIX_LEN : 0;
    return new String(k._kb,off,k._kb.length-off);
  }

  // Global HDFS initialization
  // FIXME: do not share it via classes, but initialize it by object
  static {
    Configuration conf = null;
    if( H2O.ARGS.hdfs_config != null ) {
      conf = new Configuration();
      File p = new File(H2O.ARGS.hdfs_config);
      if( !p.exists() ) H2O.die("Unable to open hdfs configuration file " + p.getAbsolutePath());
      conf.addResource(new Path(p.getAbsolutePath()));
      Log.debug("resource ", p.getAbsolutePath(), " added to the hadoop configuration");
    } else {
      conf = new Configuration();
      if( H2O.ARGS.hdfs != null && H2O.ARGS.hdfs.length() > 0 ) {
        // setup default remote Filesystem - for version 0.21 and higher
        conf.set("fs.defaultFS", H2O.ARGS.hdfs);
        // To provide compatibility with version 0.20.0 it is necessary to setup the property
        // fs.default.name which was in newer version renamed to 'fs.defaultFS'
        conf.set("fs.default.name", H2O.ARGS.hdfs);
      }
    }
    CONF = conf;
  }
  
  // Loading HDFS files
  public PersistHdfs() { _iceRoot = null; }

  // Loading/Writing ice to HDFS
  public PersistHdfs(URI uri) {
    try {
      _iceRoot = new Path(uri + "/ice" + H2O.SELF_ADDRESS.getHostAddress() + "-" + H2O.API_PORT);
      // Make the directory as-needed
      FileSystem fs = FileSystem.get(_iceRoot.toUri(), CONF);
      fs.mkdirs(_iceRoot);
    } catch( Exception e ) {
      throw Log.throwErr(e);
    }
  }
  
  /** InputStream from a HDFS-based Key */
  /*public static InputStream openStream(Key k, Job pmon) throws IOException {
    H2OHdfsInputStream res = null;
    Path p = new Path(k.toString());
    try {
      res = new H2OHdfsInputStream(p, 0, pmon);
    } catch( IOException e ) {
      try {
        Thread.sleep(1000);
      } catch( Exception ex ) {}
      Log.warn("Error while opening HDFS key " + k.toString() + ", will wait and retry.");
      res = new H2OHdfsInputStream(p, 0, pmon);
    }
    return res;
  }*/

  @Override public byte[] load(final Value v) {
    final byte[] b = MemoryManager.malloc1(v._max);
    Key k = v._key;
    long skip = k.isChunkKey() ? water.fvec.NFSFileVec.chunkOffset(k) : 0;
    final Path p = _iceRoot == null?new Path(getPathForKey(k)):new Path(_iceRoot, getIceName(v));
    final long skip_ = skip;
    run(new Callable() {
      @Override public Object call() throws Exception {
        FileSystem fs = FileSystem.get(p.toUri(), CONF);
        FSDataInputStream s = null;
        try {
          s = fs.open(p);
          // NOTE:
          // The following line degrades performance of HDFS load from S3 API: s.readFully(skip,b,0,b.length);
          // Google API's simple seek has better performance
          // Load of 300MB file via Google API ~ 14sec, via s.readFully ~ 5min (under the same condition)
          ByteStreams.skipFully(s, skip_);
          ByteStreams.readFully(s, b);
          assert v.isPersisted();
        } finally {
          FileUtils.close(s);
        }
        return null;
      }
    }, true, v._max);
  return b;
  }

  @Override public void store(Value v) {
    // Should be used only if ice goes to HDFS
    assert this == H2O.getPM().getIce();
    assert !v.isPersisted();

    byte[] m = v.memOrLoad();
    assert (m == null || m.length == v._max); // Assert not saving partial files
    store(new Path(_iceRoot, getIceName(v)), m);
    v.setdsk(); // Set as write-complete to disk
  }

  public static void store(final Path path, final byte[] data) {
    run(new Callable() {
      @Override public Object call() throws Exception {
        FileSystem fs = FileSystem.get(path.toUri(), CONF);
        fs.mkdirs(path.getParent());
        FSDataOutputStream s = fs.create(path);
        try {
          s.write(data);
        } finally {
          s.close();
        }
        return null;
      }
    }, false, data.length);
  }

  @Override public void delete(final Value v) {
    assert this == H2O.getPM().getIce();
    assert !v.isPersisted();   // Upper layers already cleared out

    run(new Callable() {
      @Override public Object call() throws Exception {
        Path p = new Path(_iceRoot, getIceName(v));
        FileSystem fs = FileSystem.get(p.toUri(), CONF);
        fs.delete(p, true);
        return null;
      }
    }, false, 0);
  }

  private static class Size {
    int _value;
  }
  
  private static void run(Callable c, boolean read, int size) {
    // Count all i/o time from here, including all retry overheads
    long start_io_ms = System.currentTimeMillis();
    while( true ) {
      try {
        long start_ns = System.nanoTime(); // Blocking i/o call timing - without counting repeats
        c.call();
        TimeLine.record_IOclose(start_ns, start_io_ms, read ? 1 : 0, size, Value.HDFS);
        break;
        // Explicitly ignore the following exceptions but
        // fail on the rest IOExceptions
      } catch( EOFException e ) {
        ignoreAndWait(e, false);
      } catch( SocketTimeoutException e ) {
        ignoreAndWait(e, false);
      } catch( IOException e ) {
        // Newer versions of Hadoop derive S3Exception from IOException
        if (e.getClass().getName().contains("S3Exception")) {
          ignoreAndWait(e, false);
        } else {
          ignoreAndWait(e, true);
        }
      } catch( RuntimeException e ) {
        // Older versions of Hadoop derive S3Exception from RuntimeException
        if (e.getClass().getName().contains("S3Exception")) {
          ignoreAndWait(e, false);
        } else {
          throw Log.throwErr(e);
        }
      } catch( Exception e ) {
        throw Log.throwErr(e);
      }
    }
  }

  private static void ignoreAndWait(final Exception e, boolean printException) {
    Log.ignore(e, "Hit HDFS reset problem, retrying...", printException);
    try {
      Thread.sleep(500);
    } catch( InterruptedException ie ) {}
  }

  public static void addFolder(Path p, ArrayList<String> keys,ArrayList<String> failed) throws IOException {
    FileSystem fs = FileSystem.get(p.toUri(), PersistHdfs.CONF);
    if(!fs.exists(p)){
      failed.add("Path does not exist: '" + p.toString() + "'");
      return;
    }
    addFolder(fs, p, keys, failed);
  }

  private static void addFolder(FileSystem fs, Path p, ArrayList<String> keys, ArrayList<String> failed) {
    try {
      if( fs == null ) return;

      Futures futures = new Futures();
      for( FileStatus file : fs.listStatus(p) ) {
        Path pfs = file.getPath();
        if( file.isDir() ) {
          addFolder(fs, pfs, keys, failed);
        } else {
          long size = file.getLen();
          Key k = null;
          keys.add((k = HDFSFileVec.make(file.getPath().toString(), file.getLen(), futures)).toString());
          Log.debug("PersistHdfs: DKV.put(" + k + ")");
        }
      }
    } catch( Exception e ) {
      Log.err(e);
      failed.add(p.toString());
    }
  }

  @Override
  public Key uriToKey(URI uri) throws IOException {
    assert "hdfs".equals(uri.getScheme()) || "s3n".equals(uri.getScheme()) : "Expected hdfs or s3n scheme, but uri is " + uri;

    FileSystem fs = FileSystem.get(uri, PersistHdfs.CONF);
    FileStatus[] fstatus = fs.listStatus(new Path(uri));
    assert fstatus.length == 1 : "Expected uri to single file, but uri is " + uri;

    return HDFSFileVec.make(fstatus[0].getPath().toString(), fstatus[0].getLen());
  }

  private static final Pattern S3N_BARE_BUCKET = Pattern.compile("s3n://[^/]*");

  @Override
  public ArrayList<String> calcTypeaheadMatches(String filter, int limit) {
    // Get HDFS configuration
    Configuration conf = PersistHdfs.CONF;
    // Handle S3N bare buckets - s3n://bucketname should be always suffixed by '/'
    // since underlying Jets3n will throw NPE, i.e. right filter name should be
    // s3n://bucketname/
    if (S3N_BARE_BUCKET.matcher(filter).matches()) {
      filter += "/";
    }
    // Output matches
    ArrayList<String> array = new ArrayList<String>();
    {
      // Filter out partials which are known to print out useless stack traces.
      String s = filter.toLowerCase();
      if ("hdfs:".equals(s)) return array;
      if ("maprfs:".equals(s)) return array;
    }
    try {
      Path p = new Path(filter);
      Path expand = p;
      if( !filter.endsWith("/") ) expand = p.getParent();
      FileSystem fs = FileSystem.get(p.toUri(), conf);
      for( FileStatus file : fs.listStatus(expand) ) {
        Path fp = file.getPath();
        if( fp.toString().startsWith(p.toString()) ) {
          array.add(fp.toString());
        }
        if( array.size() == limit) break;
      }
    } catch (Exception e) {
      Log.trace(e);
    } catch (Throwable t) {
      t.printStackTrace();
      Log.warn(t);
    }

    return array;
  }

  private boolean isBareS3NBucketWithoutTrailingSlash(String s) {
    String s2 = s.toLowerCase();
    Pattern p = Pattern.compile("s3n://[^/]*");
    Matcher m = p.matcher(s2);
    boolean b = m.matches();
    return b;
  }

  @Override
  public void importFiles(String path, ArrayList<String> files, ArrayList<String> keys, ArrayList<String> fails, ArrayList<String> dels) {
    // Fix for S3N kind of URL
    if (isBareS3NBucketWithoutTrailingSlash(path)) {
      path += "/";
    }
    Log.info("ImportHDFS processing (" + path + ")");

    // List of processed files
    try {
      // Recursively import given file/folder
      addFolder(new Path(path), keys, fails);
      files.addAll(keys);
      // write barrier was here : DKV.write_barrier();
    } catch (IOException e) {
      throw new HDFSIOException(path, PersistHdfs.CONF.toString(), e);
    }
  }

  // -------------------------------
  // Node Persistent Storage helpers
  // -------------------------------

  @Override
  public String getHomeDirectory() {
    try {
      FileSystem fs = FileSystem.get(CONF);
      return fs.getHomeDirectory().toString();
    }
    catch (Exception e) {
      return null;
    }
  }

  @Override
  public PersistEntry[] list(String path) {
    try {
      Path p = new Path(path);
      URI uri = p.toUri();
      FileSystem fs = FileSystem.get(uri, CONF);
      FileStatus[] arr1 = fs.listStatus(p);
      PersistEntry[] arr2 = new PersistEntry[arr1.length];
      for (int i = 0; i < arr1.length; i++) {
        arr2[i] = new PersistEntry();
        arr2[i]._name = arr1[i].getPath().getName();
        arr2[i]._size = arr1[i].getLen();
        arr2[i]._timestamp_millis = arr1[i].getModificationTime();
      }
      return arr2;
    }
    catch (IOException e) {
      throw new HDFSIOException(path, CONF.toString(), e);
    }
  }

  @Override
  public boolean exists(String path) {
    Path p = new Path(path);
    URI uri = p.toUri();
    try {
      FileSystem fs = FileSystem.get(uri, CONF);
      return fs.exists(p);
    }
    catch (IOException e) {
      throw new HDFSIOException(path, CONF.toString(), e);
    }
  }

  @Override
  public long length(String path) {
    Path p = new Path(path);
    URI uri = p.toUri();
    try {
      FileSystem fs = FileSystem.get(uri, CONF);
      return fs.getFileStatus(p).getLen();
    }
    catch (IOException e) {
      throw new HDFSIOException(path, CONF.toString(), e);
    }
  }

  @Override
  public InputStream open(String path) {
    Path p = new Path(path);
    URI uri = p.toUri();
    try {
      FileSystem fs = FileSystem.get(uri, CONF);
      return fs.open(p);
    }
    catch (IOException e) {
      throw new HDFSIOException(path, CONF.toString(), e);
    }
  }

  @Override
  public boolean mkdirs(String path) {
    Path p = new Path(path);
    URI uri = p.toUri();
    try {
      FileSystem fs = FileSystem.get(uri, CONF);
      return fs.mkdirs(p);
    }
    catch (IOException e) {
      throw new HDFSIOException(path, CONF.toString(), e);
    }
  }

  @Override
  public boolean rename(String fromPath, String toPath) {
    Path f = new Path(fromPath);
    Path t = new Path(toPath);
    URI uri = f.toUri();
    try {
      FileSystem fs = FileSystem.get(uri, CONF);
      if (fs.exists(t)) {
        boolean recursive = false;
        boolean success = fs.delete(t, recursive);
        if (! success) {
          Log.info("PersistHdfs rename failed (" + fromPath + " -> " + toPath +")");
          return false;
        }
      }
      return fs.rename(f, t);
    }
    catch (IOException e) {
      throw new HDFSIOException(toPath, CONF.toString(), e);
    }
  }

  @Override
  public OutputStream create(String path, boolean overwrite) {
    Path p = new Path(path);
    URI uri = p.toUri();
    try {
      FileSystem fs = FileSystem.get(uri, CONF);
      return fs.create(p, overwrite);
    }
    catch (IOException e) {
      throw new HDFSIOException(path, CONF.toString(), e);
    }
  }

  @Override
  public boolean delete(String path) {
    Path p = new Path(path);
    URI uri = p.toUri();
    try {
      FileSystem fs = FileSystem.get(uri, CONF);
      return fs.delete(p, true);
    }
    catch (IOException e) {
      throw new HDFSIOException(path, CONF.toString(), e);
    }
  }
}
