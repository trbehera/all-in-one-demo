// Copyright 2020 Intel Corporation
// SPDX-License-Identifier: Apache 2.0

package org.sdo.demo;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.tomcat.util.http.fileupload.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AioApiServet Handles authenticated API rest calls for owner files.
 */
public class AioApiServlet extends HttpServlet {

  public static final String REST_API_USERNAME = "rest.api.username";
  public static final String REST_API_PASSWORD = "rest.api.password";

  private static Logger logger = LoggerFactory.getLogger(AioApiServlet.class);

  protected boolean checkCredentials(HttpServletRequest req, HttpServletResponse res) {
    final String userParam = System.getProperty(REST_API_USERNAME);
    final String passParam = System.getProperty(REST_API_PASSWORD);
    final String basicAuth = "Basic";
    final String authHeader = req.getHeader("Authorization");

    if (authHeader == null) {
      res.addHeader("WWW-Authenticate", "Basic realm=\"sdo\"");
      res.setStatus(401);
      return false;
    }

    int pos = authHeader.indexOf(' ');
    if (pos > 0) {
      final String method = authHeader.substring(0, pos);
      if (method.compareToIgnoreCase(basicAuth) == 0) {
        final String authToken = authHeader.substring(pos + 1);
        final String authString = new String(Base64.getDecoder().decode(authToken),
            StandardCharsets.US_ASCII);
        pos = authString.indexOf(':');
        if (pos >= 0) {
          final String user = authString.substring(0, pos);
          final String password = authString.substring(pos + 1);
          if (user.equals(userParam) && password.equals(passParam)) {
            return true;
          }
        }
      }
    }
    res.setStatus(401);
    return false;
  }

  protected void syncDatabase() {
    try (AioDb db = new AioDb()) {
      db.connect();
      db.setRvInfo();
    } catch (SQLException e) {
      logger.error("Unable to Sync with H2 Database.");
    }
  }

  protected List<String> getPathElements(String uriString) {

    List<String> list = new ArrayList<>();
    URI uri = URI.create(uriString);
    String parentPath = uri.getPath();

    String pathName = getPathName(parentPath);
    while (pathName.length() > 0) {
      list.add(0, pathName);
      parentPath = getPath(parentPath);
      pathName = getPathName((parentPath));
    }
    return list;
  }

  protected String getPathName(String path) {
    String result = "";
    int pos = path.lastIndexOf('/');
    if (pos >= 0) {
      result = path.substring(pos + 1);
    }
    return result;
  }

  protected String getPath(String path) {
    String result = "";
    int pos = path.lastIndexOf('/');
    if (pos >= 0) {
      result = path.substring(0, pos);
    }
    return result;
  }

  /**
   *  Basic Path Traversal check.
   */
  public static boolean pathTraversalCheck(Path path) {
    String homeDir = new AioDb().getProperty("fs.root.dir");
    if (!(path.toString().contains("..")) && path.normalize().startsWith(homeDir)) {
      return true;
    }
    logger.error("Invalid path");
    return false;
  }

  /**
   *  Validates GUID.
   */
  public boolean checkGuid(String guid) {

    String pattern = "^[a-z0-9]{8}-[a-z0-9]{4}-[a-z0-9]{4}-[a-z0-9]{4}-[a-z0-9]{12}$";
    Pattern regex = Pattern.compile(pattern);
    if (regex.matcher(guid).matches()) {
      return true;
    }

    logger.error("Invalid GUID");
    return false;
  }

  /**
   * Basic sanity check on the filename.
   */
  public boolean checkFileNameSanity(String filename) {
    final int maxFileNameLength = Integer.parseInt(new AioDb().getProperty("filename.maxlength"));
    final String simpleFileNamePattern = "^[a-zA-Z0-9,.!?_\\-]+$";

    Pattern patternForSimpleString = Pattern
        .compile(simpleFileNamePattern, Pattern.CASE_INSENSITIVE);

    boolean checkFileName =
        patternForSimpleString.matcher(filename).find();

    if (!checkFileName) {
      logger.error("Invalid filename");
      return false;
    }

    if (filename.length() > maxFileNameLength) {
      logger.error("Invalid file name length");
      return false;
    }
    return true;
  }

  protected void deleteDirectory(HttpServletRequest req, HttpServletResponse res, Path path) {
    File dir = path.toFile();
    try {
      File[] files = dir.listFiles();
      for (File file : files) {
        file.delete();
      }
      dir.delete();
    } catch (NullPointerException e) {
      logger.error("File not found. Unable to delete the file.");
      res.setStatus(500);
    }
  }

  protected void copyFile(HttpServletRequest req, HttpServletResponse res, Path path) {

    try {
      InputStream inStream = req.getInputStream();
      Files.copy(inStream, path, StandardCopyOption.REPLACE_EXISTING);
      IOUtils.closeQuietly(inStream);
    } catch (IOException e) {
      logger.error("Error in copying the file from request stream.");
      res.setStatus(500);
    }
  }

  protected void copyFile(Path path, HttpServletResponse res) {
    if (path.toFile().exists()) {
      try {

        res.setContentType("application/octet-stream");
        OutputStream outStream = res.getOutputStream();
        Files.copy(path,
            res.getOutputStream());

        IOUtils.closeQuietly(outStream);
      } catch (IOException e) {
        logger.error("Error in copying the file from response stream.");
        res.setStatus(500);
      }
    } else {
      res.setStatus(404);
    }
  }

  protected void getFiles(Path path, HttpServletResponse res) {
    try {
      final OutputStream out = res.getOutputStream();
      final byte[] nl = new byte[]{'\n'};
      final DirectoryStream<Path> stream = Files.newDirectoryStream(path,
          new Filter<Path>() {
            @Override
            public boolean accept(Path entry) {
              return !Files.isDirectory(entry);
            }
          });
      res.setContentType("application/octet-stream");
      for (Path entry : stream) {
        final String name = entry.getFileName().toString();
        out.write(name.getBytes(StandardCharsets.US_ASCII));
        out.write(nl);
      }
    } catch (IOException e) {
      logger.error("Unable to perform getFiles operation.");
      res.setStatus(500);
    }
  }

  protected void getUuids(Path path, HttpServletResponse res) {
    try {
      final OutputStream out = res.getOutputStream();
      final byte[] nl = new byte[]{'\n'};
      final DirectoryStream<Path> stream = Files.newDirectoryStream(path,
          new Filter<Path>() {
            @Override
            public boolean accept(Path entry) {
              return Files.isDirectory(entry);
            }
          });
      res.setContentType("application/octet-stream");
      for (Path entry : stream) {
        final String name = entry.getFileName().toString();
        out.write(name.getBytes(StandardCharsets.US_ASCII));
        out.write(nl);
      }
    } catch (IOException e) {
      logger.error("Unable to perform getUUID operation");
      res.setStatus(500);
    }
  }

  protected boolean isMethodAllow(String method) {
    if (method.equals("PUT") || method.equals("DELETE") || method.equals("GET")) {
      return true;
    }
    return false;
  }

  @Override
  public void service(HttpServletRequest req, HttpServletResponse res)
      throws ServletException, IOException {

    if (checkCredentials(req, res)) {

      logger.info(req.getRequestURI());
      if (isMethodAllow(req.getMethod())) {
        AsyncContext asyncCtx = req.startAsync();

        asyncCtx.setTimeout(0);

        if (req.getMethod().equals("PUT")) {
          new Thread(() -> putAsync(asyncCtx)).start();
        } else if (req.getMethod().equals("DELETE")) {
          new Thread(() -> deleteAsync(asyncCtx)).start();
        } else if (req.getMethod().equals("GET")) {
          new Thread(() -> getAsync(asyncCtx)).start();
        }
      } else {
        res.setStatus(405);
      }
    }
  }

  /**
   * getAsync Handles HTTP GET of owner files.
   *
   * @param asyncCtx Context
   */
  public void getAsync(AsyncContext asyncCtx) {

    HttpServletRequest req = (HttpServletRequest) asyncCtx.getRequest();
    HttpServletResponse res = (HttpServletResponse) asyncCtx.getResponse();

    final String fsRootDir = new AioDb().getProperty("fs.root.dir");
    final String fsDevicesDir = new AioDb().getProperty("fs.devices.dir");
    final String fsValuesDir = new AioDb().getProperty("fs.values.dir");
    final String fsFilesDir = new AioDb().getProperty("fs.files.dir");

    List<String> list = getPathElements(req.getRequestURI());
    if (list.size() > 2 && list.get(0).equals("api") && list.get(1).equals("v1")) {
      if (list.get(2).equals("values")) {
        if (list.size() > 3) {
          String fileName = list.get(3);
          Path filePath = Paths.get(fsRootDir, fsValuesDir, fileName);
          if (checkFileNameSanity(fileName) && pathTraversalCheck(filePath)) {
            copyFile(filePath, res);
          } else {
            res.setStatus(406);
          }
        } else if (list.size() == 3) {
          Path filePath = Paths.get(fsRootDir, fsValuesDir);
          if (pathTraversalCheck(filePath)) {
            getFiles(filePath, res);
          } else {
            res.setStatus(406);
          }
        }
      } else if (list.get(2).equals("devices")) {
        if (list.size() > 4) {
          final String uuid = list.get(3);
          final String fileName = list.get(4);
          Path filePath = Paths.get(fsRootDir, fsDevicesDir, uuid, fileName);
          if (checkFileNameSanity(fileName) && pathTraversalCheck(filePath) && checkGuid(uuid)) {
            copyFile(filePath, res);
          } else {
            res.setStatus(406);
          }
        } else if (list.size() == 3) {
          Path filePath = Paths.get(fsRootDir, fsDevicesDir);
          if (pathTraversalCheck(filePath)) {
            getUuids(filePath, res);
          } else {
            res.setStatus(406);
          }

        }
      } else if (list.get(2).equals("uploads") && list.size() == 3) {
        Path filePath = Paths.get(fsRootDir, fsFilesDir);
        if (pathTraversalCheck(filePath)) {
          getFiles(filePath, res);
        } else {
          res.setStatus(406);
        }
      } else {
        res.setStatus(404);
      }
    } else {
      res.setStatus(404);
    }
    asyncCtx.complete();
  }

  /**
   * deleteAsync Handles HTTP DELETE of owner files.
   *
   * @param asyncCtx Context
   */
  public void deleteAsync(AsyncContext asyncCtx) {
    HttpServletRequest req = (HttpServletRequest) asyncCtx.getRequest();
    HttpServletResponse res = (HttpServletResponse) asyncCtx.getResponse();

    final String fsRootDir = new AioDb().getProperty("fs.root.dir");
    final String fsDevicesDir = new AioDb().getProperty("fs.devices.dir");
    final String fsValuesDir = new AioDb().getProperty("fs.values.dir");
    final String fsFilesDir = new AioDb().getProperty("fs.files.dir");

    List<String> list = getPathElements(req.getRequestURI());
    if (list.size() > 2 && list.get(0).equals("api") && list.get(1).equals("v1")) {
      if (list.get(2).equals("uploads") && list.size() > 3) {
        String fileName = list.get(3);
        Path filePath = Paths.get(fsRootDir, fsFilesDir, fileName);
        if (checkFileNameSanity(fileName) && pathTraversalCheck(filePath)) {
          filePath.toFile().delete();
        } else {
          res.setStatus(406);
        }
      } else if (list.get(2).equals("values") && list.size() > 3) {
        String fileName = list.get(3);
        Path filePath = Paths.get(fsRootDir, fsValuesDir, list.get(3));
        if (checkFileNameSanity(fileName) && pathTraversalCheck(filePath)) {
          filePath.toFile().delete();
        } else {
          res.setStatus(406);
        }

      } else if (list.get(2).equals("devices")) {
        if (list.size() > 4) {
          final String uuid = list.get(3);
          final String fileName = list.get(4);
          Path filePath = Paths.get(fsRootDir, fsDevicesDir, uuid, fileName);
          if (checkFileNameSanity(fileName) && pathTraversalCheck(filePath) && checkGuid(uuid)) {
            filePath.toFile().delete();
          } else {
            res.setStatus(406);
          }
        } else if (list.size() > 3) {
          final String uuid = list.get(3);
          Path filePath = Paths.get(fsRootDir, fsDevicesDir, uuid);
          if (pathTraversalCheck(filePath)) {
            deleteDirectory(req, res, filePath);
          } else {
            res.setStatus(406);
          }
        } else {
          res.setStatus(404);
        }
      } else {
        res.setStatus(404);
      }
    }
    asyncCtx.complete();
  }


  /**
   * putAsync Handles HTTP PUT of owner files.
   *
   * @param asyncCtx Context
   */
  public void putAsync(AsyncContext asyncCtx) {
    HttpServletRequest req = (HttpServletRequest) asyncCtx.getRequest();
    HttpServletResponse res = (HttpServletResponse) asyncCtx.getResponse();

    final String fsRootDir = new AioDb().getProperty("fs.root.dir");
    final String fsDevicesDir = new AioDb().getProperty("fs.devices.dir");
    final String fsValuesDir = new AioDb().getProperty("fs.values.dir");
    final String fsFilesDir = new AioDb().getProperty("fs.files.dir");

    List<String> list = getPathElements(req.getRequestURI());
    if (list.size() > 2 && list.get(0).equals("api") && list.get(1).equals("v1")) {
      if (list.get(2).equals("uploads") && list.size() > 3) {
        String fileName = list.get(3);
        Path filePath = Paths.get(fsRootDir, fsFilesDir, fileName);
        if (checkFileNameSanity(fileName) && pathTraversalCheck(filePath)) {
          copyFile(req, res, filePath);
        } else {
          res.setStatus(406);
        }
      } else if (list.get(2).equals("values") && list.size() > 3) {
        final String fileName = list.get(3);
        Path filePath = Paths.get(fsRootDir, fsValuesDir, fileName);
        if (checkFileNameSanity(fileName) && pathTraversalCheck(filePath)) {
          copyFile(req, res, filePath);
        } else {
          res.setStatus(406);
        }
        if (fileName.equals("redirect.properties")) {
          syncDatabase();
        }
      } else if (list.get(2).equals("devices") && list.size() > 4) {
        final String uuid = list.get(3);
        final String fileName = list.get(4);
        File guidDir = Paths.get(fsRootDir, fsDevicesDir, uuid).toFile();
        Path filePath = Paths.get(fsRootDir, fsDevicesDir, uuid, fileName);
        if (checkFileNameSanity(fileName) && pathTraversalCheck(filePath) && checkGuid(uuid)) {
          if (!guidDir.exists()) {
            guidDir.mkdir();
          }
          copyFile(req, res, filePath);
        } else {
          res.setStatus(406);
        }
      } else {
        res.setStatus(404);
      }
    } else {
      res.setStatus(404);
    }
    asyncCtx.complete();
  }
}
