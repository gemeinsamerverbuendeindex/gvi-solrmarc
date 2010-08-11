package org.solrmarc.index;
/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.File;
import java.io.FileReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.ParseException;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.sql.*;
import java.text.DateFormat;
import java.text.SimpleDateFormat;

import org.apache.log4j.Logger;
import org.marc4j.marc.ControlField;
import org.marc4j.marc.DataField;
import org.marc4j.marc.Record;
import org.marc4j.marc.Subfield;
import org.solrmarc.tools.CallNumUtils;
import org.solrmarc.tools.SolrMarcIndexerException;
import org.ini4j.Ini;

/**
 * 
 * @author Robert Haschart
 * @version $Id: VuFindIndexer.java 224 2008-11-05 19:33:21Z asnagy $
 *
 */
public class VuFindIndexer extends SolrIndexer
{
    // Initialize logging category
    static Logger logger = Logger.getLogger(VuFindIndexer.class.getName());

    // Initialize VuFind database connection (null until explicitly activated)
    private Connection vufindDatabase = null;
    private UpdateDateTracker tracker = null;

    private static SimpleDateFormat marc005date = new SimpleDateFormat("yyyyMMddHHmmss.S");
    private static SimpleDateFormat marc008date = new SimpleDateFormat("yyMMdd");

    // Shutdown flag:
    private boolean shuttingDown = false;

    /**
     * Default constructor
     * @param propertiesMapFile
     * @throws Exception
     */
    /*
    public VuFindIndexer(final String propertiesMapFile) throws FileNotFoundException, IOException, ParseException 
    {
        super(propertiesMapFile);
    }
    */
    public VuFindIndexer(final String propertiesMapFile, final String[] propertyDirs)
            throws FileNotFoundException, IOException, ParseException {
        super(propertiesMapFile, propertyDirs);
    }

    /**
     * Log an error message and throw a fatal exception.
     * @param msg
     */
    private void dieWithError(String msg)
    {
        logger.error(msg);
        throw new SolrMarcIndexerException(SolrMarcIndexerException.EXIT, msg);
    }

    /**
     * Connect to the VuFind database if we do not already have a connection.
     */
    private void connectToDatabase()
    {
        // Already connected?  Do nothing further!
        if (vufindDatabase != null) {
            return;
        }

        // Obtain the DSN from the config.ini file:
        Ini ini = new Ini();
        String configFile = "../web/conf/config.ini";
        File file = new File(configFile);
        try {
            ini.load(new FileReader(file));
        } catch (Throwable e) {
            dieWithError("Unable to access " + configFile);
        }
        String dsn = ini.get("Database", "database");

        try {
            // Parse key settings from the PHP-style DSN:
            String username = "";
            String password = "";
            if (dsn.substring(0, 8).equals("mysql://")) {
                Class.forName( "com.mysql.jdbc.Driver" ).newInstance();
                String[] parts = dsn.split("://");
                if (parts.length > 1) {
                    parts = parts[1].split("@");
                    if (parts.length > 1) {
                        dsn = "mysql://" + parts[1];
                        parts = parts[0].split(":");
                        username = parts[0];
                        if (parts.length > 1) {
                            password = parts[1];
                        }
                    }
                }
            }

            // Connect to the database:
            vufindDatabase = DriverManager.getConnection("jdbc:" + dsn, username, password);
        } catch (Throwable e) {
            dieWithError("Unable to connect to VuFind database");
        }

        Runtime.getRuntime().addShutdownHook(new VuFindShutdownThread(this));
    }

    private void disconnectFromDatabase()
    {
        if (vufindDatabase != null) {
            try {
                vufindDatabase.close();
            } catch (SQLException e) {
                System.err.println("Unable to disconnect from VuFind database");
                logger.error("Unable to disconnect from VuFind database");
            }
        }
    }

    public void shutdown()
    {
        disconnectFromDatabase();
        shuttingDown = true;
    }

    class VuFindShutdownThread extends Thread
    {
        private VuFindIndexer indexer;

        public VuFindShutdownThread(VuFindIndexer i)
        {
            indexer = i;
        }

        public void run()
        {
            indexer.shutdown();
        }
    }

    /**
     * Establish UpdateDateTracker object if not already available.
     */
    private void loadUpdateDateTracker()
    {
        if (tracker == null) {
            connectToDatabase();
            tracker = new UpdateDateTracker(vufindDatabase);
        }
    }

    /**
     * Support method for getLatestTransaction.
     * @return Date extracted from 005 (or very old date, if unavailable)
     */
    private java.util.Date normalize005Date(String input)
    {
        // Normalize "null" strings to a generic bad value:
        if (input == null) {
            input = "null";
        }

        // Try to parse the date; default to "millisecond 0" (very old date) if we can't
        // parse the data successfully.
        java.util.Date retVal;
        try {
            retVal = marc005date.parse(input);
        } catch(java.text.ParseException e) {
            retVal = new java.util.Date(0);
        }
        return retVal;
    }

    /**
     * Support method for getLatestTransaction.
     * @return Date extracted from 008 (or very old date, if unavailable)
     */
    private java.util.Date normalize008Date(String input)
    {
        // Normalize "null" strings to a generic bad value:
        if (input == null || input.length() < 6) {
            input = "null";
        }

        // Try to parse the date; default to "millisecond 0" (very old date) if we can't
        // parse the data successfully.
        java.util.Date retVal;
        try {
            retVal = marc008date.parse(input.substring(0, 6));
        } catch(java.text.ParseException e) {
            retVal = new java.util.Date(0);
        }
        return retVal;
    }

    /**
     * Extract the latest transaction date from the MARC record.  This is useful
     * for detecting when a record has changed since the last time it was indexed.
     *
     * @param record
     * @return Latest transaction date.
     */
    public java.util.Date getLatestTransaction(Record record) {
        // First try the 005 -- this is most likely to have a precise transaction date:
        Set<String> dates = getFieldList(record, "005");
        if (dates != null) {
            String current;
            Iterator<String> dateIter = dates.iterator();
            if (dateIter.hasNext()) {
                return normalize005Date(dateIter.next());
            }
        }

        // No luck with 005?  Try 008 next -- less precise, but better than nothing:
        dates = getFieldList(record, "008");
        if (dates != null) {
            String current;
            Iterator<String> dateIter = dates.iterator();
            if (dateIter.hasNext()) {
                return normalize008Date(dateIter.next());
            }
        }

        // If we got this far, we couldn't find a valid value; return an arbitrary date:
        return new java.util.Date(0);
    }

    /**
     * Determine Record Format(s)
     *
     * @param  Record          record
     * @return Set<String>     format of record
     */
    public Set<String> getFormat(final Record record){
        Set<String> result = new LinkedHashSet<String>();
        String leader = record.getLeader().toString();
        char leaderBit;
        ControlField fixedField = (ControlField) record.getVariableField("008");
        DataField title = (DataField) record.getVariableField("245");
        char formatCode = ' ';

        // check if there's an h in the 245
        if (title != null) {
            if (title.getSubfield('h') != null){
                if (title.getSubfield('h').getData().toLowerCase().contains("[electronic resource]")) {
                    result.add("Electronic");
                    return result;
                }
            }
        }

        // check the 007 - this is a repeating field
        List<ControlField> fields = record.getVariableFields("007");
        Iterator<ControlField> fieldsIter = fields.iterator();
        if (fields != null) {
            ControlField formatField;
            while(fieldsIter.hasNext()) {
                formatField = (ControlField) fieldsIter.next();
                formatCode = formatField.getData().toUpperCase().charAt(0);
                switch (formatCode) {
                    case 'A':
                        switch(formatField.getData().toUpperCase().charAt(1)) {
                            case 'D':
                                result.add("Atlas");
                                break;
                            default:
                                result.add("Map");
                                break;
                        }
                        break;
                    case 'C':
                        switch(formatField.getData().toUpperCase().charAt(1)) {
                            case 'A':
                                result.add("TapeCartridge");
                                break;
                            case 'B':
                                result.add("ChipCartridge");
                                break;
                            case 'C':
                                result.add("DiscCartridge");
                                break;
                            case 'F':
                                result.add("TapeCassette");
                                break;
                            case 'H':
                                result.add("TapeReel");
                                break;
                            case 'J':
                                result.add("FloppyDisk");
                                break;
                            case 'M':
                            case 'O':
                                result.add("CDROM");
                                break;
                            case 'R':
                                // Do not return - this will cause anything with an
                                // 856 field to be labeled as "Electronic"
                                break;
                            default:
                                result.add("Software");
                                break;
                        }
                        break;
                    case 'D':
                        result.add("Globe");
                        break;
                    case 'F':
                        result.add("Braille");
                        break;
                    case 'G':
                        switch(formatField.getData().toUpperCase().charAt(1)) {
                            case 'C':
                            case 'D':
                                result.add("Filmstrip");
                                break;
                            case 'T':
                                result.add("Transparency");
                                break;
                            default:
                                result.add("Slide");
                                break;
                        }
                        break;
                    case 'H':
                        result.add("Microfilm");
                        break;
                    case 'K':
                        switch(formatField.getData().toUpperCase().charAt(1)) {
                            case 'C':
                                result.add("Collage");
                                break;
                            case 'D':
                                result.add("Drawing");
                                break;
                            case 'E':
                                result.add("Painting");
                                break;
                            case 'F':
                                result.add("Print");
                                break;
                            case 'G':
                                result.add("Photonegative");
                                break;
                            case 'J':
                                result.add("Print");
                                break;
                            case 'L':
                                result.add("Drawing");
                                break;
                            case 'O':
                                result.add("FlashCard");
                                break;
                            case 'N':
                                result.add("Chart");
                                break;
                            default:
                                result.add("Photo");
                                break;
                        }
                        break;
                    case 'M':
                        switch(formatField.getData().toUpperCase().charAt(1)) {
                            case 'F':
                                result.add("VideoCassette");
                                break;
                            case 'R':
                                result.add("Filmstrip");
                                break;
                            default:
                                result.add("MotionPicture");
                                break;
                        }
                        break;
                    case 'O':
                        result.add("Kit");
                        break;
                    case 'Q':
                        result.add("MusicalScore");
                        break;
                    case 'R':
                        result.add("SensorImage");
                        break;
                    case 'S':
                        switch(formatField.getData().toUpperCase().charAt(1)) {
                            case 'D':
                                result.add("SoundDisc");
                                break;
                            case 'S':
                                result.add("SoundCassette");
                                break;
                            default:
                                result.add("SoundRecording");
                                break;
                        }
                        break;
                    case 'V':
                        switch(formatField.getData().toUpperCase().charAt(1)) {
                            case 'C':
                                result.add("VideoCartridge");
                                break;
                            case 'D':
                                result.add("VideoDisc");
                                break;
                            case 'F':
                                result.add("VideoCassette");
                                break;
                            case 'R':
                                result.add("VideoReel");
                                break;
                            default:
                                result.add("Video");
                                break;
                        }
                        break;
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        }

        // check the Leader at position 6
        leaderBit = leader.charAt(6);
        switch (Character.toUpperCase(leaderBit)) {
            case 'C':
            case 'D':
                result.add("MusicalScore");
                break;
            case 'E':
            case 'F':
                result.add("Map");
                break;
            case 'G':
                result.add("Slide");
                break;
            case 'I':
                result.add("SoundRecording");
                break;
            case 'J':
                result.add("MusicRecording");
                break;
            case 'K':
                result.add("Photo");
                break;
            case 'M':
                result.add("Electronic");
                break;
            case 'O':
            case 'P':
                result.add("Kit");
                break;
            case 'R':
                result.add("PhysicalObject");
                break;
            case 'T':
                result.add("Manuscript");
                break;
        }
        if (!result.isEmpty()) {
            return result;
        }

        // check the Leader at position 7
        leaderBit = leader.charAt(7);
        switch (Character.toUpperCase(leaderBit)) {
            // Monograph
            case 'M':
                if (formatCode == 'C') {
                    result.add("eBook");
                } else {
                    result.add("Book");
                }
                break;
            // Serial
            case 'S':
                // Look in 008 to determine what type of Continuing Resource
                formatCode = fixedField.getData().toUpperCase().charAt(21);
                switch (formatCode) {
                    case 'N':
                        result.add("Newspaper");
                        break;
                    case 'P':
                        result.add("Journal");
                        break;
                    default:
                        result.add("Serial");
                        break;
                }
        }

        // Nothing worked!
        if (result.isEmpty()) {
            result.add("Unknown");
        }
        
        return result;
    }

    /**
     * Extract the call number label from a record
     * @param record
     * @return Call number label
     */
    public String getFullCallNumber(final Record record) {

        return(getFullCallNumber(record, "099ab:090ab:050ab"));
    }

    /**
     * Extract the call number label from a record
     * @param record
     * @return Call number label
     */
    public String getFullCallNumber(final Record record, String fieldSpec) {

        String val = getFirstFieldVal(record, fieldSpec);

        if (val != null) {
            return val.toUpperCase().replaceAll(" ", "");
        } else {
            return val;
        }
    }

    /**
     * Extract the call number label from a record
     * @param record
     * @return Call number label
     */
    public String getCallNumberLabel(final Record record) {       
    
        return getCallNumberLabel(record, "090a:050a");
    }
    
    /**
     * Extract the call number label from a record
     * @param record
     * @return Call number label
     */
    public String getCallNumberLabel(final Record record, String fieldSpec) {
        
        String val = getFirstFieldVal(record, fieldSpec);
        
        if (val != null) {
            int dotPos = val.indexOf(".");
            if (dotPos > 0) {
                val = val.substring(0, dotPos);
            }
            return val.toUpperCase();
        } else {
            return val;
        }
    }
    
    /**
     * Extract the subject component of the call number
     *
     * Can return null
     *
     * @param record
     * @return Call number label
     */
    public String getCallNumberSubject(final Record record) {

        return(getCallNumberSubject(record, "090a:050a"));
    }
    
    /**
     * Extract the subject component of the call number
     *
     * Can return null
     *
     * @param record
     * @return Call number label
     */
    public String getCallNumberSubject(final Record record, String fieldSpec) {

        String val = getFirstFieldVal(record, fieldSpec);

        if (val != null) {
            String [] callNumberSubject = val.toUpperCase().split("[^A-Z]+");
            if (callNumberSubject.length > 0)
            {
                return callNumberSubject[0];
            }
        }
        return(null);
    }

    /**
     * Determine if a record is illustrated.
     *
     * @param  Record          record
     * @return String   "Illustrated" or "Not Illustrated"
     */
    public String isIllustrated(Record record) {
        String leader = record.getLeader().toString();

        // Does the leader indicate this is a "language material" that might have extra
        // illustration details in the fixed fields?
        if (leader.charAt(6) == 'a') {
            String currentCode = "";         // for use in loops below

            // List of 008/18-21 codes that indicate illustrations:
            String illusCodes = "abcdefghijklmop";

            // Check the illustration characters of the 008:
            ControlField fixedField = (ControlField) record.getVariableField("008");
            if (fixedField != null) {
                String fixedFieldText = fixedField.getData().toLowerCase();
                for (int i = 18; i <= 21; i++) {
                    if (i < fixedFieldText.length()) {
                        currentCode = fixedFieldText.substring(i, i + 1);
                        if (illusCodes.contains(currentCode)) {
                            return "Illustrated";
                        }
                    }
                }
            }

            // Now check if any 006 fields apply:
            List<ControlField> fields = record.getVariableFields("006");
            Iterator<ControlField> fieldsIter = fields.iterator();
            if (fields != null) {
                ControlField formatField;
                while(fieldsIter.hasNext()) {
                    fixedField = (ControlField) fieldsIter.next();
                    String fixedFieldText = fixedField.getData().toLowerCase();
                    for (int i = 1; i <= 4; i++) {
                         if (i < fixedFieldText.length()) {
                            currentCode = fixedFieldText.substring(i, i + 1);
                            if (illusCodes.contains(currentCode)) {
                                return "Illustrated";
                            }
                        }
                    }
                }
            }
        }

        // Now check for interesting strings in 300 subfield b:
        List<ControlField> fields = record.getVariableFields("300");
        Iterator<ControlField> fieldsIter = fields.iterator();
        if (fields != null) {
            DataField physical;
            while(fieldsIter.hasNext()) {
                physical = (DataField) fieldsIter.next();
                List<Subfield> subfields = physical.getSubfields('b');
                Iterator<Subfield> subfieldsIter = subfields.iterator();
                if (subfields != null) {
                    String desc;
                    while (subfieldsIter.hasNext()) {
                        desc = subfieldsIter.next().getData().toLowerCase();
                        if (desc.contains("ill.") || desc.contains("illus.")) {
                            return "Illustrated";
                        }
                    }
                }
            }
        }

        // If we made it this far, we found no sign of illustrations:
        return "Not Illustrated";
    }

    /**
     * Extract a numeric portion of the Dewey decimal call number
     *
     * Can return null
     *
     * @param record
     * @param fieldSpec - which MARC fields / subfields need to be analyzed
     * @param precisionStr - a decimal number (represented in string format) showing the 
     *  desired precision of the returned number; i.e. 100 to round to nearest hundred, 
     *  10 to round to nearest ten, 0.1 to round to nearest tenth, etc.
     * @return Set containing requested numeric portions of Dewey decimal call numbers
     */
    public Set<String> getDeweyNumber(Record record, String fieldSpec, String precisionStr) {
        // Initialize our return value:
        Set<String> result = new LinkedHashSet<String>();

        // Precision comes in as a string, but we need to convert it to a float:        
        float precision = Float.parseFloat(precisionStr);

        // Loop through the specified MARC fields:
        Set<String> input = getFieldList(record, fieldSpec);
        Iterator<String> iter = input.iterator();
        while (iter.hasNext()) {
            // Get the current string to work on:
            String current = iter.next();

            if (CallNumUtils.isValidDewey(current)) {
                // Convert the numeric portion of the call number into a float:
                float currentVal = Float.parseFloat(CallNumUtils.getDeweyB4Cutter(current));

                // Round the call number value to the specified precision:
                Float finalVal = new Float(Math.floor(currentVal / precision) * precision);

                // Convert the rounded value back to a string (with leading zeros) and save it:
                result.add(CallNumUtils.normalizeFloat(finalVal.toString(), 3, -1));
            }
        }

        // If we found no call number matches, return null; otherwise, return our results:
        if (result.isEmpty())
            return null;
        return result;
    }

    /**
     * Normalize Dewey numbers for searching purposes (uppercase/stripped spaces)
     *
     * Can return null
     *
     * @param record
     * @param fieldSpec - which MARC fields / subfields need to be analyzed
     * @return Set containing normalized Dewey numbers extracted from specified fields.
     */
    public Set<String> getDeweySearchable(Record record, String fieldSpec) {
        // Initialize our return value:
        Set<String> result = new LinkedHashSet<String>();

        // Loop through the specified MARC fields:
        Set<String> input = getFieldList(record, fieldSpec);
        Iterator<String> iter = input.iterator();
        while (iter.hasNext()) {
            // Get the current string to work on:
            String current = iter.next();

            // Add valid strings to the set, normalizing them to be all uppercase
            // and free from whitespace.
            if (CallNumUtils.isValidDewey(current)) {
                result.add(current.toUpperCase().replaceAll(" ", ""));
            }
        }

        // If we found no call numbers, return null; otherwise, return our results:
        if (result.isEmpty())
            return null;
        return result;
    }

    /**
     * Normalize Dewey numbers for sorting purposes (use only the first valid number!)
     *
     * Can return null
     *
     * @param record
     * @param fieldSpec - which MARC fields / subfields need to be analyzed
     * @return String containing the first valid Dewey number encountered, normalized
     *         for sorting purposes.
     */
    public String getDeweySortable(Record record, String fieldSpec) {
        // Loop through the specified MARC fields:
        Set<String> input = getFieldList(record, fieldSpec);
        Iterator<String> iter = input.iterator();
        while (iter.hasNext()) {
            // Get the current string to work on:
            String current = iter.next();

            // If this is a valid Dewey number, return the sortable shelf key:
            if (CallNumUtils.isValidDewey(current)) {
                return CallNumUtils.getDeweyShelfKey(current);
            }
        }

        // If we made it this far, we didn't find a valid sortable Dewey number:
        return null;
    }

    /**
     * Update the index date in the database for the specified core/ID pair.  We
     * maintain a database of "first/last indexed" times separately from Solr to
     * allow the history of our indexing activity to be stored permanently in a
     * fashion that can survive even a total Solr rebuild.
     */
    public void updateTracker(String core, String id, java.util.Date latestTransaction)
    {
        // Initialize date tracker if not already initialized:
        loadUpdateDateTracker();

        // Update the database (if necessary):
        try {
            tracker.index(core, id, latestTransaction);
        } catch (java.sql.SQLException e) {
            // If we're in the process of shutting down, an error is expected:
            if (!shuttingDown) {
                dieWithError("Unexpected database error");
            }
        }
    }

    /**
     * Get the "first indexed" date for the current record.  (This is the first
     * time that SolrMarc ever encountered this particular record).
     *
     * @param record
     * @param fieldSpec
     * @param core
     * @return ID string
     */
    public String getFirstIndexed(Record record, String fieldSpec, String core) {
        // Update the database, then send back the first indexed date:
        updateTracker(core, getFirstFieldVal(record, fieldSpec), getLatestTransaction(record));
        return tracker.getFirstIndexed();
    }

    /**
     * Get the "first indexed" date for the current record.  (This is the first
     * time that SolrMarc ever encountered this particular record).
     *
     * @param record
     * @param fieldSpec
     * @return ID string
     */
    public String getFirstIndexed(Record record, String fieldSpec) {
        return getFirstIndexed(record, fieldSpec, "biblio");
    }

    /**
     * Get the "first indexed" date for the current record.  (This is the first
     * time that SolrMarc ever encountered this particular record).
     *
     * @param record
     * @return ID string
     */
    public String getFirstIndexed(Record record) {
        return getFirstIndexed(record, "001", "biblio");
    }

    /**
     * Get the "last indexed" date for the current record.  (This is the last time
     * the record changed from SolrMarc's perspective).
     *
     * @param record
     * @param fieldSpec
     * @param core
     * @return ID string
     */
    public String getLastIndexed(Record record, String fieldSpec, String core) {
        // Update the database, then send back the last indexed date:
        updateTracker(core, getFirstFieldVal(record, fieldSpec), getLatestTransaction(record));
        return tracker.getLastIndexed();
    }

    /**
     * Get the "last indexed" date for the current record.  (This is the last time
     * the record changed from SolrMarc's perspective).
     *
     * @param record
     * @param fieldSpec
     * @return ID string
     */
    public String getLastIndexed(Record record, String fieldSpec) {
        return getLastIndexed(record, fieldSpec, "biblio");
    }

    /**
     * Get the "last indexed" date for the current record.  (This is the last time
     * the record changed from SolrMarc's perspective).
     *
     * @param record
     * @return ID string
     */
    public String getLastIndexed(Record record) {
        return getLastIndexed(record, "001", "biblio");
    }
}