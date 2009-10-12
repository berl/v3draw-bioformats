//
// ScanrReader.java
//

/*
OME Bio-Formats package for reading and converting biological file formats.
Copyright (C) 2005-@year@ UW-Madison LOCI and Glencoe Software, Inc.

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package loci.formats.in;

import java.io.IOException;
import java.util.Arrays;
import java.util.Vector;

import loci.common.DataTools;
import loci.common.Location;
import loci.common.RandomAccessInputStream;
import loci.common.XMLTools;
import loci.formats.CoreMetadata;
import loci.formats.FormatException;
import loci.formats.FormatReader;
import loci.formats.FormatTools;
import loci.formats.MetadataTools;
import loci.formats.meta.FilterMetadata;
import loci.formats.meta.MetadataStore;
import loci.formats.tiff.IFD;
import loci.formats.tiff.TiffParser;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/**
 * ScanrReader is the file format reader for Olympus ScanR datasets.
 *
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="https://skyking.microscopy.wisc.edu/trac/java/browser/trunk/components/bio-formats/src/loci/formats/in/ScanrReader.java">Trac</a>,
 * <a href="https://skyking.microscopy.wisc.edu/svn/java/trunk/components/bio-formats/src/loci/formats/in/ScanrReader.java">SVN</a></dd></dl>
 *
 * @author Melissa Linkert melissa at glencoesoftware.com
 */
public class ScanrReader extends FormatReader {

  // -- Constants --

  private static final String XML_FILE = "experiment_descriptor.xml";
  private static final String EXPERIMENT_FILE = "experiment_descriptor.dat";
  private static final String ACQUISITION_FILE = "AcquisitionLog.dat";

  // -- Fields --

  private Vector<String> metadataFiles = new Vector<String>();
  private int wellRows, wellColumns;
  private int fieldRows, fieldColumns;
  private Vector<String> channelNames = new Vector<String>();
  private String plateName;

  private String[] tiffs;
  private MinimalTiffReader reader;

  // -- Constructor --

  /** Constructs a new ScanR reader. */
  public ScanrReader() {
    super("Olympus ScanR", new String[] {"dat", "xml", "tif"});
    domains = new String[] {FormatTools.HCS_DOMAIN};
    suffixSufficient = false;
  }

  // -- IFormatReader API methods --

  /* @see loci.formats.IFormatReader#isThisType(String, boolean) */
  public boolean isThisType(String name, boolean open) {
    String localName = new Location(name).getName();
    if (localName.equals(XML_FILE) || localName.equals(EXPERIMENT_FILE) ||
      localName.equals(ACQUISITION_FILE))
    {
      return true;
    }

    return super.isThisType(name, open);
  }

  /* @see loci.formats.IFormatReader#isThisType(RandomAccessInputStream) */
  public boolean isThisType(RandomAccessInputStream stream) throws IOException {
    TiffParser p = new TiffParser(stream);
    IFD ifd = p.getFirstIFD();
    if (ifd == null) return false;

    Object s = ifd.getIFDValue(IFD.SOFTWARE);
    if (s == null) return false;
    String software = s instanceof String[] ? ((String[]) s)[0] : s.toString();
    return software.equals("National Instruments IMAQ");
  }

  /* @see loci.formats.IFormatReader#getSeriesUsedFiles(boolean) */
  public String[] getSeriesUsedFiles(boolean noPixels) {
    FormatTools.assertId(currentId, true, 1);

    Vector<String> files = new Vector<String>();
    for (String file : metadataFiles) {
      files.add(file);
    }

    if (!noPixels && tiffs != null) {
      int offset = getSeries() * getImageCount();
      for (int i=0; i<getImageCount(); i++) {
        files.add(tiffs[offset + i]);
      }
    }

    return files.toArray(new String[files.size()]);
  }

  /* @see loci.formats.IFormatReader#close(boolean) */
  public void close(boolean fileOnly) throws IOException {
    super.close(fileOnly);
    if (!fileOnly) {
      if (reader != null) {
        reader.close();
      }
      reader = null;
      tiffs = null;
      plateName = null;
      channelNames.clear();
      fieldRows = fieldColumns = 0;
      wellRows = wellColumns = 0;
      metadataFiles.clear();
    }
  }

  /**
   * @see loci.formats.IFormatReader#openBytes(int, byte[], int, int, int, int)
   */
  public byte[] openBytes(int no, byte[] buf, int x, int y, int w, int h)
    throws FormatException, IOException
  {
    FormatTools.checkPlaneParameters(this, no, buf.length, x, y, w, h);

    int index = getSeries() * getImageCount() + no;
    if (tiffs[index] != null) {
      reader.setId(tiffs[index]);
      reader.openBytes(0, buf, x, y, w, h);
      reader.close();
    }

    return buf;
  }

  // -- Internal FormatReader API methods --

  /* @see loci.formats.FormatReader#initFile(String) */
  protected void initFile(String id) throws FormatException, IOException {
    debug("ScanrReader.initFile(" + id + ")");
    super.initFile(id);

    // make sure we have the .xml file
    if (!checkSuffix(id, "xml")) {
      Location parent = new Location(id).getAbsoluteFile().getParentFile();
      if (checkSuffix(id, "tif")) {
        parent = parent.getParentFile();
      }
      String[] list = parent.list();
      for (String file : list) {
        if (file.equals(XML_FILE)) {
          id = new Location(parent, file).getAbsolutePath();
          super.initFile(id);
          break;
        }
      }
      if (!checkSuffix(id, "xml")) {
        throw new FormatException("Could not find " + XML_FILE + " in " +
          parent.getAbsolutePath());
      }
    }

    Location dir = new Location(id).getAbsoluteFile().getParentFile();
    String[] list = dir.list(true);

    for (String file : list) {
      Location f = new Location(dir, file);
      if (!f.isDirectory()) {
        metadataFiles.add(f.getAbsolutePath());
      }
    }

    // parse XML metadata

    String xml = DataTools.readFile(id);
    XMLTools.parseXML(xml, new ScanrHandler());

    int nChannels = getSizeC() == 0 ? 1 : getSizeC();
    int nSlices = getSizeZ() == 0 ? 1 : getSizeZ();
    int nTimepoints = getSizeT() == 0 ? 1 : getSizeT();
    int nSeries = wellRows * wellColumns * fieldRows * fieldColumns;

    tiffs = new String[nChannels * nSlices * nTimepoints * nSeries];

    // get list of TIFF files

    dir = new Location(dir, "data");
    list = dir.list(true);

    int next = 0;
    for (int i=0; i<getSeriesCount(); i++) {
      int well = i / (fieldRows * fieldColumns);
      String wellPos = String.valueOf(well + 1);
      while (wellPos.length() < 5) wellPos = "0" + wellPos;
      wellPos = "W" + wellPos;

      for (int z=0; z<nSlices; z++) {
        String zPos = String.valueOf(z);
        while (zPos.length() < 5) zPos = "0" + zPos;
        zPos = "Z" + zPos;

        for (int t=0; t<nTimepoints; t++) {
          String tPos = String.valueOf(t);
          while (tPos.length() < 5) tPos = "0" + tPos;
          tPos = "T" + tPos;

          for (int c=0; c<nChannels; c++) {
            for (String file : list) {
              if (file.indexOf(wellPos) != -1 && file.indexOf(zPos) != -1 &&
                file.indexOf(tPos) != -1 &&
                file.indexOf(channelNames.get(c)) != -1)
              {
                tiffs[next++] = new Location(dir, file).getAbsolutePath();
              }
            }
          }
        }
      }
    }

    reader = new MinimalTiffReader();
    reader.setId(tiffs[0]);
    int sizeX = reader.getSizeX();
    int sizeY = reader.getSizeY();
    int pixelType = reader.getPixelType();
    boolean rgb = reader.isRGB();
    boolean interleaved = reader.isInterleaved();
    boolean indexed = reader.isIndexed();
    boolean littleEndian = reader.isLittleEndian();

    reader.close();

    core = new CoreMetadata[nSeries];
    for (int i=0; i<nSeries; i++) {
      core[i] = new CoreMetadata();
      core[i].sizeC = nChannels;
      core[i].sizeZ = nSlices;
      core[i].sizeT = nTimepoints;
      core[i].sizeX = sizeX;
      core[i].sizeY = sizeY;
      core[i].pixelType = pixelType;
      core[i].rgb = rgb;
      core[i].interleaved = interleaved;
      core[i].indexed = indexed;
      core[i].littleEndian = littleEndian;
      core[i].dimensionOrder = "XYCTZ";
      core[i].imageCount = nSlices * nTimepoints * nChannels;
    }

    MetadataStore store =
      new FilterMetadata(getMetadataStore(), isMetadataFiltered());
    MetadataTools.populatePixels(store, this);

    // populate LogicalChannel data

    for (int i=0; i<getSeriesCount(); i++) {
      for (int c=0; c<getSizeC(); c++) {
        store.setLogicalChannelName(channelNames.get(c), i, c);
      }
    }

    store.setPlateRowNamingConvention("A", 0);
    store.setPlateColumnNamingConvention("1", 0);
    store.setPlateName(plateName, 0);

    int nFields = fieldRows * fieldColumns;

    for (int i=0; i<getSeriesCount(); i++) {
      MetadataTools.setDefaultCreationDate(store, id, i);

      int field = i % nFields;
      int well = i / nFields;

      int wellRow = well / wellColumns;
      int wellCol = well % wellColumns;

      store.setWellColumn(new Integer(wellCol), 0, well);
      store.setWellRow(new Integer(wellRow), 0, well);

      store.setWellSampleIndex(new Integer(i), 0, well, field);

      String name = "Well " + String.valueOf((char) ('A' + wellRow)) +
        (wellCol + 1) + ", Field " + (field + 1);
      store.setImageName(name, i);
    }

  }

  // -- Helper class --

  class ScanrHandler extends DefaultHandler {
    private String key, value;
    private String qName;

    // -- DefaultHandler API methods --

    public void characters(char[] ch, int start, int length) {
      String v = new String(ch, start, length);
      if (v.trim().length() == 0) return;
      if (qName.equals("Name")) {
        key = v;
      }
      else if (qName.equals("Val")) {
        value = v;
        addGlobalMeta(key, value);

        if (key.equals("custom column")) {
          wellColumns = Integer.parseInt(value) / fieldColumns;
        }
        else if (key.equals("custom row")) {
          wellRows = Integer.parseInt(value) / fieldRows;
        }
        else if (key.equals("columns/well")) {
          fieldColumns = Integer.parseInt(value);
        }
        else if (key.equals("rows/well")) {
          fieldRows = Integer.parseInt(value);
        }
        else if (key.equals("# slices")) {
          core[0].sizeZ = Integer.parseInt(value);
        }
        else if (key.equals("timeloop real")) {
          core[0].sizeT = Integer.parseInt(value);
        }
        else if (key.equals("name")) {
          channelNames.add(value);
        }
        else if (key.equals("plate name")) {
          plateName = value;
        }
      }
      else if (qName.equals("Dimsize")) {
        if (key.equals("multiple_channel_typedef")) {
          core[0].sizeC = Integer.parseInt(v);
        }
      }
    }

    public void startElement(String uri, String localName, String qName,
      Attributes attributes)
    {
      this.qName = qName;
    }

  }

}