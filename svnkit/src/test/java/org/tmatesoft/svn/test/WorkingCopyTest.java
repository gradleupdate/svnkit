package org.tmatesoft.svn.test;

import java.io.File;

import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.internal.wc.SVNFileListUtil;
import org.tmatesoft.svn.core.internal.wc.SVNFileUtil;
import org.tmatesoft.svn.core.wc.SVNClientManager;
import org.tmatesoft.svn.core.wc.SVNCommitClient;
import org.tmatesoft.svn.core.wc.SVNCopyClient;
import org.tmatesoft.svn.core.wc.SVNCopySource;
import org.tmatesoft.svn.core.wc.SVNRevision;
import org.tmatesoft.svn.core.wc.SVNUpdateClient;
import org.tmatesoft.svn.core.wc.SVNWCClient;

public class WorkingCopyTest {

    public static WorkingCopyTest createWithInitialization(String testName) throws SVNException {
        final WorkingCopyTest workingCopyTest = new WorkingCopyTest(testName);
        workingCopyTest.initialize();
        return workingCopyTest;
    }

    private final String testName;

    private final TestOptions testOptions;
    private File workingDirectory;

    private File testDirectory;
    private SVNClientManager clientManager;

    public WorkingCopyTest(String testName) {
        this(testName, TestOptions.getInstance());
    }

    public WorkingCopyTest(String testName, TestOptions testOptions) {
        this.testName = testName;
        this.testOptions = testOptions;
    }

    private void initialize() throws SVNException {
        checkOptions();
        cleanup();
    }

    private void checkOptions() {
        if  (getTestOptions().getRepositoryUrl() == null) {
            throw new RuntimeException("Unable to start the test: repository URL is not specified.");
        }
    }

    private void cleanup() throws SVNException {
        SVNFileUtil.deleteAll(getTestDirectory(), null);
    }

    public long checkoutLatestRevision() throws SVNException {
        final SVNUpdateClient updateClient = getClientManager().getUpdateClient();

        final long revisionCheckedOut = updateClient.doCheckout(getRepositoryUrl(),
                getWorkingCopyDirectory(),
                SVNRevision.HEAD,
                SVNRevision.HEAD,
                SVNDepth.INFINITY,
                true);

        checkWorkingCopyConsistency();

        return revisionCheckedOut;
    }

    public void updateToRevision(long revision) throws SVNException {
        final SVNUpdateClient updateClient = getClientManager().getUpdateClient();

        updateClient.doUpdate(getWorkingCopyDirectory(),
                SVNRevision.create(revision),
                SVNDepth.INFINITY,
                true,
                true);

        checkWorkingCopyConsistency();
    }

    public File findAnyDirectory() {
        final File workingCopyDirectory = getWorkingCopyDirectory();
        final File[] files = SVNFileListUtil.listFiles(workingCopyDirectory);
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory() && !file.getName().equals(SVNFileUtil.getAdminDirectoryName())) {
                    return file;
                }
            }
        }

        throw new RuntimeException("The repository contains no directories, run the tests with another repository");
    }

    public File findAnotherDirectory(File directory) {
        final File workingCopyDirectory = getWorkingCopyDirectory();
        final File[] files = SVNFileListUtil.listFiles(workingCopyDirectory);
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory() && !file.getName().equals(SVNFileUtil.getAdminDirectoryName()) && !file.getAbsolutePath().equals(directory.getAbsolutePath())) {
                    return file;
                }
            }
        }

        throw new RuntimeException("The repository root should contain at least two directories, please run the tests with another repository");
    }

    public void copyAsChild(File directory, File anotherDirectory) throws SVNException {
        final SVNCopyClient copyClient = getClientManager().getCopyClient();

        copyClient.doCopy(new SVNCopySource[]{
                new SVNCopySource(SVNRevision.WORKING, SVNRevision.WORKING, directory)},
                anotherDirectory,
                false, false, false);

        checkWorkingCopyConsistency();
    }

    public void commit(String commitMessage) throws SVNException {
        final SVNCommitClient commitClient = getClientManager().getCommitClient();

        commitClient.doCommit(new File[]{getWorkingCopyDirectory()},
                false,
                commitMessage,
                null, null,
                false, true,
                SVNDepth.INFINITY);

        checkWorkingCopyConsistency();
    }

    public void add(File file) throws SVNException {
        final SVNWCClient wcClient = getClientManager().getWCClient();

        wcClient.doAdd(file, false, false, false, SVNDepth.INFINITY, true, true, true);

        checkWorkingCopyConsistency();
    }

    public void revert() throws SVNException {
        final SVNWCClient wcClient = getClientManager().getWCClient();

        wcClient.doRevert(new File[]{getWorkingCopyDirectory()}, SVNDepth.INFINITY, null);

        checkWorkingCopyConsistency();
    }

    private void checkWorkingCopyConsistency() {
        //TODO
    }

    public void dispose() {
        if (clientManager != null) {
            clientManager.dispose();
        }
    }

    private SVNClientManager getClientManager() {
        if (clientManager == null) {
            clientManager = SVNClientManager.newInstance();
        }
        return clientManager;
    }

    private String getTestName() {
        return testName;
    }

    private TestOptions getTestOptions() {
        return testOptions;
    }

    private SVNURL getRepositoryUrl() {
        return getTestOptions().getRepositoryUrl();
    }

    private File getTempDirectory() {
        return getTestOptions().getTempDirectory();
    }

    private File getWorkingCopyDirectory() {
        if (workingDirectory == null) {
            workingDirectory = TestUtil.createDirectory(getTestDirectory(), "wc").getAbsoluteFile();
        }
        return workingDirectory;
    }

    private File getTestDirectory() {
        if (testDirectory == null) {
            testDirectory = createTestDirectory();
        }
        return testDirectory;
    }

    private File createTestDirectory() {
        final File testDirectory = new File(getTempDirectory(), getTestName());
        testDirectory.mkdirs();
        return testDirectory;
    }
}