/*
 * ====================================================================
 * Copyright (c) 2004-2006 TMate Software Ltd.  All rights reserved.
 *
 * This software is licensed as described in the file COPYING, which
 * you should have received as part of this distribution.  The terms
 * are also available at http://tmate.org/svn/license.html.
 * If newer versions of this license are posted there, you may use a
 * newer version instead, at your option.
 * ====================================================================
 */
package org.tmatesoft.svn.core.internal.io.fs;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;

import org.tmatesoft.svn.core.SVNErrorCode;
import org.tmatesoft.svn.core.SVNErrorMessage;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNLock;
import org.tmatesoft.svn.core.SVNNodeKind;
import org.tmatesoft.svn.core.internal.util.SVNPathUtil;
import org.tmatesoft.svn.core.internal.util.SVNTimeUtil;
import org.tmatesoft.svn.core.internal.util.SVNUUIDGenerator;
import org.tmatesoft.svn.core.internal.wc.SVNErrorManager;
import org.tmatesoft.svn.core.internal.wc.SVNFileType;
import org.tmatesoft.svn.core.internal.wc.SVNFileUtil;
import org.tmatesoft.svn.core.internal.wc.SVNProperties;
import org.tmatesoft.svn.core.io.ISVNLockHandler;

/**
 * @version 1.0
 * @author  TMate Software Ltd.
 */
public class FSFS {
    
    private static final int REPOSITORY_FORMAT = 3;
    private static final int DB_FORMAT = 1;
    private static final String DB_TYPE = "fsfs";
    
    private String myUUID;
    
    private File myRepositoryRoot;
    private File myRevisionsRoot;
    private File myRevisionPropertiesRoot;
    private File myTransactionsRoot;
    private File myLocksRoot;
    private File myDBRoot;
    private File myWriteLockFile;
    private File myCurrentFile;

    public FSFS(File repositoryRoot) {
        myRepositoryRoot = repositoryRoot;
        myDBRoot = new File(myRepositoryRoot, "db");
        myRevisionsRoot = new File(myDBRoot, "revs");
        myRevisionPropertiesRoot = new File(myDBRoot, "revprops");
        myTransactionsRoot = new File(myDBRoot, "transactions");
        myWriteLockFile = new File(myDBRoot, "write-lock");
        myLocksRoot = new File(myDBRoot, "locks");
    }
    
    public void open() throws SVNException {
        // repo format /root/format
        FSFile formatFile = new FSFile(new File(myRepositoryRoot, "format"));
        int format = -1;
        try {
            format = formatFile.readInt();
        } finally {
            formatFile.close();
        }
        if (format != REPOSITORY_FORMAT) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.REPOS_UNSUPPORTED_VERSION, "Expected format ''{0,number,integer}'' of repository; found format ''{1,number,integer}''", new Object[]{new Integer(REPOSITORY_FORMAT), new Integer(format)});
            SVNErrorManager.error(err);
        }
        // fs format /root/db/format
        formatFile = new FSFile(new File(myDBRoot, "format"));
        try {
            format = formatFile.readInt();
        } finally {
            formatFile.close();
        }
        if (format != DB_FORMAT) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_UNSUPPORTED_FORMAT, "Expected FS format ''{0,number,integer}''; found format ''{1,number,integer}''", new Object[]{new Integer(DB_FORMAT), new Integer(format)});
            SVNErrorManager.error(err);
        }

        // fs type /root/db/fs-type
        formatFile = new FSFile(new File(myDBRoot, "fs-type"));
        String fsType = null;
        try {
            fsType = formatFile.readLine(128);    
        } finally {
            formatFile.close();
        }
        if (!DB_TYPE.equals(fsType)) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_UNKNOWN_FS_TYPE, "Unsupported fs type ''{0}''", fsType);
            SVNErrorManager.error(err);
        }

        File dbCurrentFile = new File(myDBRoot, "current");
        if(!(dbCurrentFile.exists() && dbCurrentFile.canRead())){
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.IO_ERROR, "Can''t open file ''{0}''", dbCurrentFile);
            SVNErrorManager.error(err);
        }
        
    }
    
    public String getUUID() throws SVNException {
        if(myUUID == null){
            // uuid
            FSFile formatFile = new FSFile(new File(myDBRoot, "uuid"));
            try {
                myUUID = formatFile.readLine(38);
            } finally {
                formatFile.close();
            }
        }

        return myUUID;
    }
    
    public File getWriteLockFile() {
        return myWriteLockFile;
    }
    
    public long getYoungestRevision() throws SVNException {
        FSFile file = new FSFile(getCurrentFile());
        try {
            String line = file.readLine(180);
            int spaceIndex = line.indexOf(' ');
            if (spaceIndex > 0) {
                return Long.parseLong(line.substring(0, spaceIndex));
            }
        } catch (NumberFormatException nfe) {
            //
        } finally {
            file.close();
        }
        SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_CORRUPT, "Can''t parse revision number in file ''{0}''", file); 
        SVNErrorManager.error(err);
        return - 1;
    }
    
    protected File getCurrentFile(){
        if(myCurrentFile == null){
            myCurrentFile = new File(myDBRoot, "current"); 
        }
        return myCurrentFile;
    }
    
    public File getDBRoot(){
        return myDBRoot;
    }
    
    public Map getRevisionProperties(long revision) throws SVNException {
        FSFile file = new FSFile(getRevisionPropertiesFile(revision));
        try {
            return file.readProperties(false);
        } finally {
            file.close();
        }
    }

    protected FSFile getTransactionRevisionNodePropertiesFile(FSID id) {
        File revNodePropsFile = new File(getTransactionDir(id.getTxnID()), "node." + id.getNodeID() + "." + id.getCopyID() + ".props");
        return new FSFile(revNodePropsFile);
    }

    public FSRevisionRoot createRevisionRoot(long revision) {
        return new FSRevisionRoot(this, revision);
    }
    
    public FSTransactionRoot createTransactionRoot(String txnID, int flags) {
        return new FSTransactionRoot(this, txnID, flags);
    }

    public FSRevisionNode getRevisionNode(FSID id) throws SVNException  {
        FSFile revisionFile = null;

        if (id.isTxn()) {
            File file = new File(getTransactionDir(id.getTxnID()), "node." + id.getNodeID() + "." + id.getCopyID());
            revisionFile = new FSFile(file);
        } else {
            revisionFile = getRevisionFile(id.getRevision());
            revisionFile.seek(id.getOffset());
        }

        Map headers = null;
        try {
            headers = revisionFile.readHeader();
        } finally{
            revisionFile.close();
        }

        return FSRevisionNode.fromMap(headers);
    }
    
    public Map getDirContents(FSRevisionNode revNode) throws SVNException {
        if (revNode.getTextRepresentation() != null && revNode.getTextRepresentation().isTxn()) {
            FSFile childrenFile = getTransactionRevisionNodeChildrenFile(revNode.getId());
            Map entries = null;
            try {
                Map rawEntries = childrenFile.readProperties(false);
                rawEntries.putAll(childrenFile.readProperties(true));
                
                Object[] keys = rawEntries.keySet().toArray();
                for(int i = 0; i < keys.length; i++){
                    if(rawEntries.get(keys[i]) == null){
                        rawEntries.remove(keys[i]);
                    }
                }
            
                entries = parsePlainRepresentation(rawEntries, true);
            } finally {
                childrenFile.close();
            }
            return entries;
        } else if (revNode.getTextRepresentation() != null) {
            FSRepresentation textRep = revNode.getTextRepresentation();
            FSFile revisionFile = null;
            
            try {
                revisionFile = openAndSeekRepresentation(textRep);
                String repHeader = revisionFile.readLine(160);
                
                if(!"PLAIN".equals(repHeader)){
                    SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_CORRUPT, "Malformed representation header");
                    SVNErrorManager.error(err);
                }
                
                revisionFile.resetDigest();
                Map rawEntries = revisionFile.readProperties(false);
                String checksum = revisionFile.digest();
               
                if (!checksum.equals(textRep.getHexDigest())) {
                    SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_CORRUPT, "Checksum mismatch while reading representation:\n   expected:  {0}\n     actual:  {1}", new Object[]{checksum, textRep.getHexDigest()});
                    SVNErrorManager.error(err);
                }

                return parsePlainRepresentation(rawEntries, false);
            } finally {
                if(revisionFile != null){
                    revisionFile.close();
                }
            }
        }
        return new HashMap();// returns an empty map, must not be null!!
    }

    private Map parsePlainRepresentation(Map entries, boolean mayContainNulls) throws SVNException {
        Map representationMap = new HashMap();
        Object[] names = entries.keySet().toArray();
        for (int i = 0; i < names.length; i++) {
            String name = (String) names[i];
            String unparsedEntry = (String) entries.get(names[i]);
            
            if(unparsedEntry == null && mayContainNulls){
                continue;
            }
            
            FSEntry nextRepEntry = parseRepEntryValue(name, unparsedEntry);
            if (nextRepEntry == null) {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_CORRUPT, "Directory entry corrupt");
                SVNErrorManager.error(err);
            }
            representationMap.put(name, nextRepEntry);
        }
        return representationMap;
    }

    private FSEntry parseRepEntryValue(String name, String value) {
        if (value == null) {
            return null;
        }
        int spaceInd = value.indexOf(' ');
        if (spaceInd == -1) {
            return null;
        }
        String kind = value.substring(0, spaceInd);
        String rawID = value.substring(spaceInd + 1);
        
        SVNNodeKind type = SVNNodeKind.parseKind(kind);
        FSID id = FSID.fromString(rawID);
        if ((type != SVNNodeKind.DIR && type != SVNNodeKind.FILE) || id == null) {
            return null;
        }
        return new FSEntry(id, type, name);
    }

    public Map getProperties(FSRevisionNode revNode) throws SVNException {
        if (revNode.getPropsRepresentation() != null && revNode.getPropsRepresentation().isTxn()) {
            FSFile propsFile = null;
            try {
                propsFile = getTransactionRevisionNodePropertiesFile(revNode.getId());
                return propsFile.readProperties(false);
            } finally {
                if(propsFile != null){
                    propsFile.close();
                }
            }
        } else if (revNode.getPropsRepresentation() != null) {
            FSRepresentation propsRep = revNode.getPropsRepresentation();
            FSFile revisionFile = null;
            
            try {
                revisionFile = openAndSeekRepresentation(propsRep);
                String repHeader = revisionFile.readLine(160);
                
                if(!"PLAIN".equals(repHeader)){
                    SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_CORRUPT, "Malformed representation header");
                    SVNErrorManager.error(err);
                }

                revisionFile.resetDigest();
                Map props = revisionFile.readProperties(false);
                String checksum = revisionFile.digest();

                if (!checksum.equals(propsRep.getHexDigest())) {
                    SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_CORRUPT, "Checksum mismatch while reading representation:\n   expected:  {0}\n     actual:  {1}", new Object[]{checksum, propsRep.getHexDigest()});
                    SVNErrorManager.error(err);
                }
                return props;
            } finally {
                if(revisionFile != null){
                    revisionFile.close();
                }
            }
        }
        return new HashMap();// no properties? return an empty map
    }

    public String[] getNextRevisionIDs() throws SVNException {
        String[] ids = new String[2];
        FSFile currentFile = new FSFile(getCurrentFile());
        String idsLine = null;
        
        try{
            idsLine = currentFile.readLine(80);
        }finally{
            currentFile.close();
        }
        
        if (idsLine == null || idsLine.length() == 0) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_CORRUPT, "Corrupt current file");
            SVNErrorManager.error(err);
        }
        
        int spaceInd = idsLine.indexOf(' ');
        if (spaceInd == -1) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_CORRUPT, "Corrupt current file");
            SVNErrorManager.error(err);
        }
        
        idsLine = idsLine.substring(spaceInd + 1);
        spaceInd = idsLine.indexOf(' ');
        if (spaceInd == -1) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_CORRUPT, "Corrupt current file");
            SVNErrorManager.error(err);
        }
        String nodeID = idsLine.substring(0, spaceInd);
        String copyID = idsLine.substring(spaceInd + 1);
        
        ids[0] = nodeID;
        ids[1] = copyID;
        return ids;
    }

    protected FSFile getRevisionFile(long revision)  throws SVNException {
        File revisionFile = new File(myRevisionsRoot, String.valueOf(revision));
        if (!revisionFile.exists()) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_NO_SUCH_REVISION, "No such revision {0,number,integer}", new Long(revision));
            SVNErrorManager.error(err);
        }
        return new FSFile(revisionFile);
    }

    public File getNewRevisionFile(long revision) throws SVNException {
        File revFile = new File(myRevisionsRoot, String.valueOf(revision));
        if (revFile.exists()) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_CONFLICT, "Revision already exists");
            SVNErrorManager.error(err);
        }
        return revFile;
    }

    public File getNewRevisionPropertiesFile(long revision) throws SVNException {
        File revPropsFile = new File(myRevisionPropertiesRoot, String.valueOf(revision));
        if (revPropsFile.exists()) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_CONFLICT, "Revision already exists");
            SVNErrorManager.error(err);
        }
        return revPropsFile;
    }

    public File getTransactionDir(String txnID) {
        return new File(myTransactionsRoot, txnID + ".txn");
    }
    
    public File getTransactionsParentDir(){
        return myTransactionsRoot;
    }
    
    protected FSFile getTransactionChangesFile(String txnID) {
        File file = new File(getTransactionDir(txnID), "changes");
        return new FSFile(file);
    }

    protected FSFile getTransactionRevisionNodeChildrenFile(FSID txnID) {
        File childrenFile = new File(getTransactionDir(txnID.getTxnID()), "node." + txnID.getNodeID() + "." + txnID.getCopyID() + ".children");
        return new FSFile(childrenFile);
    }
    
    public File getRevisionPropertiesFile(long revision) throws SVNException {
        File file = new File(myRevisionPropertiesRoot, String.valueOf(revision));
        if (!file.exists()) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_NO_SUCH_REVISION, "No such revision {0,number,integer}", new Long(revision));
            SVNErrorManager.error(err);
        }
        return file;
    }
    
    protected FSFile getTransactionRevisionPrototypeFile(String txnID) {
        File revFile = new File(getTransactionDir(txnID), "rev");
        return new FSFile(revFile);
    }

    public File getRepositoryRoot(){
        return myRepositoryRoot;
    }
    
    public FSFile openAndSeekRepresentation(FSRepresentation rep) throws SVNException {
        if (!rep.isTxn()) {
            return openAndSeekRevision(rep.getRevision(), rep.getOffset());
        }
        return openAndSeekTransaction(rep);
    }

    private FSFile openAndSeekTransaction(FSRepresentation rep) {
        FSFile file = getTransactionRevisionPrototypeFile(rep.getTxnId());
        file.seek(rep.getOffset());
        return file;
    }

    private FSFile openAndSeekRevision(long revision, long offset) throws SVNException {
        FSFile file = getRevisionFile(revision);
        file.seek(offset);
        return file;
    }

    public File getNextIDsFile(String txnID) {
        return new File(getTransactionDir(txnID), "next-ids");
    }

    public void writeNextIDs(String txnID, String nodeID, String copyID) throws SVNException {
        OutputStream nextIdsFile = null;
        try {
            nextIdsFile = SVNFileUtil.openFileForWriting(getNextIDsFile(txnID));
            String ids = nodeID + " " + copyID + "\n";
            nextIdsFile.write(ids.getBytes());
        } catch (IOException ioe) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.IO_ERROR, ioe.getLocalizedMessage());
            SVNErrorManager.error(err, ioe);
        } finally {
            SVNFileUtil.closeFile(nextIdsFile);
        }
    }

    public void setTransactionProperty(String txnID, String propertyName, String propertyValue) throws SVNException {
        SVNProperties revProps = new SVNProperties(getTransactionPropertiesFile(txnID), null);
        revProps.setPropertyValue(propertyName, propertyValue);
    }

    public void setRevisionProperty(long revision, String propertyName, String propertyNewValue, String propertyOldValue, String userName, String action) throws SVNException {
        FSHooks.runPreRevPropChangeHook(myRepositoryRoot, propertyName, propertyNewValue, userName, revision, action);
        SVNProperties revProps = new SVNProperties(getRevisionPropertiesFile(revision), null);
        revProps.setPropertyValue(propertyName, propertyNewValue);
        FSHooks.runPostRevPropChangeHook(myRepositoryRoot, propertyName, propertyOldValue, userName, revision, action);
    }

    public Map getTransactionProperties(String txnID) throws SVNException {
        FSFile txnPropsFile = new FSFile(getTransactionPropertiesFile(txnID));
        try {
            return txnPropsFile.readProperties(false);
        } finally {
            txnPropsFile.close();
        }
    }

    public File getTransactionPropertiesFile(String txnID) {
        return new File(getTransactionDir(txnID), "props");
    }

    public void createNewTxnNodeRevisionFromRevision(String txnID, FSRevisionNode sourceNode) throws SVNException {
        if (sourceNode.getId().isTxn()) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_CORRUPT, "Copying from transactions not allowed");
            SVNErrorManager.error(err);
        }
        FSRevisionNode revNode = FSRevisionNode.dumpRevisionNode(sourceNode);
        revNode.setPredecessorId(sourceNode.getId());
        revNode.setCount(revNode.getCount() + 1);
        revNode.setCopyFromPath(null);
        revNode.setCopyFromRevision(FSConstants.SVN_INVALID_REVNUM);
        revNode.setId(FSID.createTxnId(sourceNode.getId().getNodeID(), sourceNode.getId().getCopyID(), txnID));
        putTxnRevisionNode(revNode.getId(), revNode);
    }

    public void putTxnRevisionNode(FSID id, FSRevisionNode revNode) throws SVNException {
        if (!id.isTxn()) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_CORRUPT, "Attempted to write to non-transaction");
            SVNErrorManager.error(err);
        }
        OutputStream revNodeFile = null;
        try {
            revNodeFile = SVNFileUtil.openFileForWriting(getTransactionRevNodeFile(id));
            writeTxnNodeRevision(revNodeFile, revNode);
        } catch (IOException ioe) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.IO_ERROR, ioe.getLocalizedMessage());
            SVNErrorManager.error(err, ioe);
        } finally {
            SVNFileUtil.closeFile(revNodeFile);
        }
    }

    public File getTransactionRevNodeFile(FSID id) {
        return new File(getTransactionDir(id.getTxnID()), "node." + id.getNodeID() + "." + id.getCopyID());
    }

    public void writeTxnNodeRevision(OutputStream revNodeFile, FSRevisionNode revNode) throws IOException {
        String id = FSConstants.HEADER_ID + ": " + revNode.getId() + "\n";
        revNodeFile.write(id.getBytes());
        String type = FSConstants.HEADER_TYPE + ": " + revNode.getType() + "\n";
        revNodeFile.write(type.getBytes());
        if (revNode.getPredecessorId() != null) {
            String predId = FSConstants.HEADER_PRED + ": " + revNode.getPredecessorId() + "\n";
            revNodeFile.write(predId.getBytes());
        }
        String count = FSConstants.HEADER_COUNT + ": " + revNode.getCount() + "\n";
        revNodeFile.write(count.getBytes());
        if (revNode.getTextRepresentation() != null) {
            String textRepresentation = FSConstants.HEADER_TEXT + ": "
                    + (revNode.getTextRepresentation().getTxnId() != null && revNode.getType() == SVNNodeKind.DIR ? "-1" : revNode.getTextRepresentation().toString()) + "\n";
            revNodeFile.write(textRepresentation.getBytes());
        }
        if (revNode.getPropsRepresentation() != null) {
            String propsRepresentation = FSConstants.HEADER_PROPS + ": " + (revNode.getPropsRepresentation().getTxnId() != null ? "-1" : revNode.getPropsRepresentation().toString()) + "\n";
            revNodeFile.write(propsRepresentation.getBytes());
        }
        String cpath = FSConstants.HEADER_CPATH + ": " + revNode.getCreatedPath() + "\n";
        revNodeFile.write(cpath.getBytes());
        if (revNode.getCopyFromPath() != null) {
            String copyFromPath = FSConstants.HEADER_COPYFROM + ": " + revNode.getCopyFromRevision() + " " + revNode.getCopyFromPath() + "\n";
            revNodeFile.write(copyFromPath.getBytes());
        }
        if (revNode.getCopyRootRevision() != revNode.getId().getRevision() || !revNode.getCopyRootPath().equals(revNode.getCreatedPath())) {
            String copyroot = FSConstants.HEADER_COPYROOT + ": " + revNode.getCopyRootRevision() + " " + revNode.getCopyRootPath() + "\n";
            revNodeFile.write(copyroot.getBytes());
        }
        revNodeFile.write("\n".getBytes());
    }
    
    public SVNLock getLock(String repositoryPath, boolean haveWriteLock) throws SVNException {
        SVNLock lock = fetchLockFromDigestFile(null, repositoryPath, null);
        
        if (lock == null) {
            SVNErrorManager.error(FSErrors.errorNoSuchLock(repositoryPath, this));
        }
        
        Date current = new Date(System.currentTimeMillis());

        if (lock.getExpirationDate() != null && current.compareTo(lock.getExpirationDate()) > 0) {
            if (haveWriteLock) {
                deleteLock(lock);
            }
            SVNErrorManager.error(FSErrors.errorLockExpired(lock.getID(), this));
        }
        return lock;
    }

    public void deleteLock(SVNLock lock) throws SVNException {
        String reposPath = lock.getPath();
        String childToKill = null;
        Collection children = new ArrayList();
        while (true) {
            fetchLockFromDigestFile(null, reposPath, children);
            if (childToKill != null) {
                children.remove(childToKill);
            }

            if (children.size() == 0) {
                childToKill = getDigestFromRepositoryPath(reposPath);
                File digestFile = getDigestFileFromRepositoryPath(reposPath);
                SVNFileUtil.deleteFile(digestFile);
            } else {
                writeDigestLockFile(null, children, reposPath);
                childToKill = null;
            }

            if ("/".equals(reposPath)) {
                break;
            }
            
            reposPath = SVNPathUtil.removeTail(reposPath);
            
            if ("".equals(reposPath)) {
                reposPath = "/";
            }
            children.clear();
        }
    }

    private boolean ensureDirExists(File dir, boolean create) {
        if (!dir.exists() && create == true) {
            return dir.mkdirs();
        } else if (!dir.exists()) {
            return false;
        }
        return true;
    }

    private void writeDigestLockFile(SVNLock lock, Collection children, String repositoryPath) throws SVNException {
        if (!ensureDirExists(myLocksRoot, true)) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.UNKNOWN, "Can't create a directory at ''{0}''", myLocksRoot);
            SVNErrorManager.error(err);
        }
        
        File digestLockFile = getDigestFileFromRepositoryPath(repositoryPath);
        String digest = getDigestFromRepositoryPath(repositoryPath);
        File lockDigestSubdir = new File(myLocksRoot, digest.substring(0, FSConstants.DIGEST_SUBDIR_LEN));//FSRepositoryUtil.getDigestSubdirectoryFromDigest(, reposRootDir);

        if (!ensureDirExists(lockDigestSubdir, true)) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.UNKNOWN, "Can't create a directory at ''{0}''", lockDigestSubdir);
            SVNErrorManager.error(err);
        }
        
        Map props = new HashMap();
        
        if (lock != null) {
            props.put(FSConstants.PATH_LOCK_KEY, lock.getPath());
            props.put(FSConstants.OWNER_LOCK_KEY, lock.getOwner());
            props.put(FSConstants.TOKEN_LOCK_KEY, lock.getID());
            props.put(FSConstants.IS_DAV_COMMENT_LOCK_KEY, "0");
            if (lock.getComment() != null) {
                props.put(FSConstants.COMMENT_LOCK_KEY, lock.getComment());
            }
            if (lock.getCreationDate() != null) {
                props.put(FSConstants.CREATION_DATE_LOCK_KEY, SVNTimeUtil.formatDate(lock.getCreationDate()));
            }
            if (lock.getExpirationDate() != null) {
                props.put(FSConstants.EXPIRATION_DATE_LOCK_KEY, SVNTimeUtil.formatDate(lock.getExpirationDate()));
            }
        }
        if (children != null && children.size() > 0) {
            Object[] digests = children.toArray();
            StringBuffer value = new StringBuffer();
            for (int i = 0; i < digests.length; i++) {
                value.append(digests[i]);
                value.append('\n');
            }
            props.put(FSConstants.CHILDREN_LOCK_KEY, value.toString());
        }
        try {
            SVNProperties.setProperties(props, digestLockFile);
        } catch (SVNException svne) {
            SVNErrorMessage err = svne.getErrorMessage().wrap("Cannot write lock/entries hashfile ''{0}''", digestLockFile);
            SVNErrorManager.error(err, svne);
        }
    }

    public void walkDigestFiles(File digestFile, ISVNLockHandler getLocksHandler, boolean haveWriteLock) throws SVNException {
        Collection children = new LinkedList();
        SVNLock lock = fetchLockFromDigestFile(digestFile, null, children);

        if (lock != null) {
            Date current = new Date(System.currentTimeMillis());
            if (lock.getExpirationDate() == null || current.compareTo(lock.getExpirationDate()) < 0) {
                getLocksHandler.handleLock(null, lock, null);
            } else if (haveWriteLock) {
                deleteLock(lock);
            }
        }

        if (children.isEmpty()) {
            return;
        }
        
        for (Iterator entries = children.iterator(); entries.hasNext();) {
            String digestName = (String) entries.next();
            File parent = new File(myLocksRoot, digestName.substring(0, FSConstants.DIGEST_SUBDIR_LEN));
            File childDigestFile = new File(parent, digestName);
            walkDigestFiles(childDigestFile, getLocksHandler, haveWriteLock);
        }
    }

    public SVNLock getLockHelper(String repositoryPath, boolean haveWriteLock) throws SVNException {
        SVNLock lock = null;
        try {
            lock = getLock(repositoryPath, haveWriteLock);
        } catch (SVNException svne) {
            if (svne.getErrorMessage().getErrorCode() == SVNErrorCode.FS_NO_SUCH_LOCK || svne.getErrorMessage().getErrorCode() == SVNErrorCode.FS_LOCK_EXPIRED) {
                return null;
            }
            throw svne;
        }
        return lock;
    }

    public SVNLock fetchLockFromDigestFile(File digestFile, String repositoryPath, Collection children) throws SVNException {
        File digestLockFile = digestFile == null ? getDigestFileFromRepositoryPath(repositoryPath) : digestFile;
        Map lockProps = null;

        if(digestLockFile.exists()){
            FSFile reader = new FSFile(digestLockFile);
            try {
                lockProps = reader.readProperties(false);
            } catch (SVNException svne) {
                SVNErrorMessage err = svne.getErrorMessage().wrap("Can't parse lock/entries hashfile ''{0}''", digestLockFile);
                SVNErrorManager.error(err);
            }finally{
                reader.close();
            }
        }else{
            lockProps = Collections.EMPTY_MAP;
        }
        
        SVNLock lock = null;
        String lockPath = (String) lockProps.get(FSConstants.PATH_LOCK_KEY);
        if (lockPath != null) {
            String lockToken = (String) lockProps.get(FSConstants.TOKEN_LOCK_KEY);
            if (lockToken == null) {
                SVNErrorManager.error(FSErrors.errorCorruptLockFile(lockPath, this));
            }
            String lockOwner = (String) lockProps.get(FSConstants.OWNER_LOCK_KEY);
            if (lockOwner == null) {
                SVNErrorManager.error(FSErrors.errorCorruptLockFile(lockPath, this));
            }
            String davComment = (String) lockProps.get(FSConstants.IS_DAV_COMMENT_LOCK_KEY);
            if (davComment == null) {
                SVNErrorManager.error(FSErrors.errorCorruptLockFile(lockPath, this));
            }
            String creationTime = (String) lockProps.get(FSConstants.CREATION_DATE_LOCK_KEY);
            if (creationTime == null) {
                SVNErrorManager.error(FSErrors.errorCorruptLockFile(lockPath, this));
            }
            Date creationDate = SVNTimeUtil.parseDateString(creationTime);
            String expirationTime = (String) lockProps.get(FSConstants.EXPIRATION_DATE_LOCK_KEY);
            Date expirationDate = null;
            if (expirationTime != null) {
                expirationDate = SVNTimeUtil.parseDateString(expirationTime);
            }
            String comment = (String) lockProps.get(FSConstants.COMMENT_LOCK_KEY);
            lock = new SVNLock(lockPath, lockToken, lockOwner, comment, creationDate, expirationDate);
        }
        
        String childEntries = (String) lockProps.get(FSConstants.CHILDREN_LOCK_KEY);
        if (children != null && childEntries != null) {
            String[] digests = childEntries.split("\n");
            for (int i = 0; i < digests.length; i++) {
                children.add(digests[i]);
            }
        }
        return lock;
    }
    
    public File getDigestFileFromRepositoryPath(String repositoryPath) throws SVNException {
        String digest = getDigestFromRepositoryPath(repositoryPath);
        File parent = new File(myLocksRoot, digest.substring(0, FSConstants.DIGEST_SUBDIR_LEN));
        return new File(parent, digest);
    }

    public String getDigestFromRepositoryPath(String repositoryPath) throws SVNException {
        MessageDigest digestFromPath = null;
        try {
            digestFromPath = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException nsae) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.IO_ERROR, "MD5 implementation not found: {0}", nsae.getLocalizedMessage());
            SVNErrorManager.error(err, nsae);
        }
        digestFromPath.update(repositoryPath.getBytes());
        return SVNFileUtil.toHexDigest(digestFromPath); 
    }

    public void unlockPath(String path, String token, String username, boolean breakLock) throws SVNException {
        String[] paths = {path};

        if (!breakLock && username == null) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_NO_USER, "Cannot unlock path ''{0}'', no authenticated username available", path);
            SVNErrorManager.error(err);
        }
        
        FSHooks.runPreUnlockHook(myRepositoryRoot, path, username);

        FSWriteLock writeLock = FSWriteLock.getWriteLock(this);
        
        synchronized (writeLock) {
            try {
                writeLock.lock();
                unlock(path, token, username, breakLock);
            } finally {
                writeLock.unlock();
                FSWriteLock.realease(writeLock);
            }
        }

        try {
            FSHooks.runPostUnlockHook(myRepositoryRoot, paths, username);
        } catch (SVNException svne) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.REPOS_POST_UNLOCK_HOOK_FAILED, "Unlock succeeded, but post-unlock hook failed");
            err.setChildErrorMessage(svne.getErrorMessage());
            SVNErrorManager.error(err, svne);
        }
    }

    private void unlock(String path, String token, String username, boolean breakLock) throws SVNException {
        SVNLock lock = getLock(path, true);
        if (!breakLock) {
            if (token == null || !token.equals(lock.getID())) {
                SVNErrorManager.error(FSErrors.errorNoSuchLock(lock.getPath(), this));
            }
            if (username == null || "".equals(username)) {
                SVNErrorManager.error(FSErrors.errorNoUser(this));
            }
            if (!username.equals(lock.getOwner())) {
                SVNErrorManager.error(FSErrors.errorLockOwnerMismatch(username, lock.getOwner(), this));
            }
        }
        deleteLock(lock);
    }

    private SVNLock lock(String path, String token, String username, String comment, Date expirationDate, long currentRevision, boolean stealLock) throws SVNException {
        long youngestRev = getYoungestRevision();
        FSRevisionRoot root = createRevisionRoot(youngestRev);
        SVNNodeKind kind = root.checkNodeKind(path); 

        if (kind == SVNNodeKind.DIR) {
            SVNErrorManager.error(FSErrors.errorNotFile(path, this));
        } else if (kind == SVNNodeKind.NONE) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_NOT_FOUND, "Path ''{0}'' doesn't exist in HEAD revision", path);
            SVNErrorManager.error(err);
        }

        if (username == null || "".equals(username)) {
            SVNErrorManager.error(FSErrors.errorNoUser(this));
        }

        if (FSRepository.isValidRevision(currentRevision)) {
            FSRevisionNode node = root.getRevisionNode(path);
            long createdRev = node.getId().getRevision();
            if (FSRepository.isInvalidRevision(createdRev)) {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_OUT_OF_DATE, "Path ''{0}'' doesn't exist in HEAD revision", path);
                SVNErrorManager.error(err);
            }
            if (currentRevision < createdRev) {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_OUT_OF_DATE, "Lock failed: newer version of ''{0}'' exists", path);
                SVNErrorManager.error(err);
            }
        }
        
        SVNLock existingLock = getLockHelper(path, true);
        
        if (existingLock != null) {
            if (!stealLock) {
                SVNErrorManager.error(FSErrors.errorPathAlreadyLocked(existingLock.getPath(), existingLock.getOwner(), this));
            } else {
                deleteLock(existingLock);
            }
        }

        SVNLock lock = null;
        if (token == null) {
            String uuid = SVNUUIDGenerator.formatUUID(SVNUUIDGenerator.generateUUID());
            token = FSConstants.SVN_OPAQUE_LOCK_TOKEN + uuid;
            lock = new SVNLock(path, token, username, comment, new Date(System.currentTimeMillis()), expirationDate);
        } else {
            lock = new SVNLock(path, token, username, comment, new Date(System.currentTimeMillis()), expirationDate);
        }
        
        setLock(lock);
        return lock;
    }
    
    public SVNLock lockPath(String path, String token, String username, String comment, Date expirationDate, long currentRevision, boolean stealLock) throws SVNException {
        String[] paths = {path};
        
        if (username == null) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_NO_USER, "Cannot lock path ''{0}'', no authenticated username available.", path);
            SVNErrorManager.error(err);
        }

        FSHooks.runPreLockHook(myRepositoryRoot, path, username);
        SVNLock lock = null;
        
        FSWriteLock writeLock = FSWriteLock.getWriteLock(this);

        synchronized (writeLock) {
            try {
                writeLock.lock();
                lock = lock(path, token, username, comment, expirationDate, currentRevision, stealLock);
            } finally {
                writeLock.unlock();
                FSWriteLock.realease(writeLock);
            }
        }

        try {
            FSHooks.runPostLockHook(myRepositoryRoot, paths, username);
        } catch (SVNException svne) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.REPOS_POST_LOCK_HOOK_FAILED, "Lock succeeded, but post-lock hook failed");
            err.setChildErrorMessage(svne.getErrorMessage());
            SVNErrorManager.error(err, svne);
        }
        return lock;
    }
    
    private void setLock(SVNLock lock) throws SVNException {
        if (lock == null) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.UNKNOWN, "FATAL error: attempted to set a null lock");
            SVNErrorManager.error(err);
        }
        String lastChild = "";
        String path = lock.getPath();
        Collection children = new ArrayList();
        while (true) {
            String digestFileName = getDigestFromRepositoryPath(path);
            SVNLock fetchedLock = fetchLockFromDigestFile(null, path, children);

            if (lock != null) {
                fetchedLock = lock;
                lock = null;
                lastChild = digestFileName;
            } else {
                if (!children.isEmpty() && children.contains(lastChild)) {
                    break;
                }
                children.add(lastChild);
            }
            
            writeDigestLockFile(fetchedLock, children, path);
            
            if ("/".equals(path)) {
                break;
            }
            path = SVNPathUtil.removeTail(path);
            
            if ("".equals(path)) {
                path = "/";
            }
            children.clear();
        }
    }
    
    public void writePathInfoToReportFile(OutputStream tmpFileOS, String target, String path, String linkPath, String lockToken, long revision, boolean startEmpty) throws IOException {
        String anchorRelativePath = SVNPathUtil.append(target, path);
        String linkPathRep = linkPath != null ? "+" + linkPath.length() + ":" + linkPath : "-";
        String revisionRep = FSRepository.isValidRevision(revision) ? "+" + revision + ":" : "-";
        String lockTokenRep = lockToken != null ? "+" + lockToken.length() + ":" + lockToken : "-";
        String startEmptyRep = startEmpty ? "+" : "-";
        String fullRepresentation = "+" + anchorRelativePath.length() + ":" + anchorRelativePath + linkPathRep + revisionRep + startEmptyRep + lockTokenRep;
        tmpFileOS.write(fullRepresentation.getBytes());
    }

    public static File findRepositoryRoot(File path) {
        if (path == null) {
            path = new File("");
        }
        File rootPath = path;
        while (!isRepositoryRoot(rootPath)) {
            rootPath = rootPath.getParentFile();
            if (rootPath == null) {
                return null;
            }
        }
        return rootPath;
    }

    private static boolean isRepositoryRoot(File candidatePath) {
        File formatFile = new File(candidatePath, FSConstants.SVN_REPOS_FORMAT_FILE);
        SVNFileType fileType = SVNFileType.getType(formatFile);
        if (fileType != SVNFileType.FILE) {
            return false;
        }
        File dbFile = new File(candidatePath, FSConstants.SVN_REPOS_DB_DIR);
        fileType = SVNFileType.getType(dbFile);
        if (fileType != SVNFileType.DIRECTORY && fileType != SVNFileType.SYMLINK) {
            return false;
        }
        return true;
    }

}
