package org.tmatesoft.svn.test;

import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.tmatesoft.svn.core.SVNCommitInfo;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.internal.wc.SVNFileUtil;
import org.tmatesoft.svn.core.wc.*;
import org.tmatesoft.svn.core.wc2.*;

import java.io.File;
import java.util.Collection;
import java.util.Map;

public class MergeTest {

    @Ignore("Temporarily ignored")
    @Test
    public void testConflictResolution() throws Exception {
        final TestOptions options = TestOptions.getInstance();

        final SvnOperationFactory svnOperationFactory = new SvnOperationFactory();
        final Sandbox sandbox = Sandbox.createWithCleanup(getTestName() + ".testConflictResolution", options);
        try {
            final SVNURL url = sandbox.createSvnRepository();

            final CommitBuilder commitBuilder1 = new CommitBuilder(url);
            commitBuilder1.addFile("file", "base".getBytes());
            SVNCommitInfo commitInfo1 = commitBuilder1.commit();

            final CommitBuilder commitBuilder2 = new CommitBuilder(url);
            commitBuilder2.changeFile("file", "theirs".getBytes());
            commitBuilder2.commit();

            final WorkingCopy workingCopy = sandbox.checkoutNewWorkingCopy(url, commitInfo1.getNewRevision());
            final File workingCopyDirectory = workingCopy.getWorkingCopyDirectory();

            final File file = new File(workingCopyDirectory, "file");
            TestUtil.writeFileContentsString(file, "mine");

            final SvnUpdate update = svnOperationFactory.createUpdate();
            update.setSingleTarget(SvnTarget.fromFile(workingCopyDirectory));
            update.run();

            runResolve(svnOperationFactory, file, SVNConflictChoice.MINE_CONFLICT);

            final String fileContentsString = TestUtil.readFileContentsString(file);
            Assert.assertEquals("mine", fileContentsString);

        } finally {
            svnOperationFactory.dispose();
            sandbox.dispose();
        }
    }

    @Test
    public void testRemoteAddOverUnversionedFileConflictResolution() throws Exception {
        final TestOptions options = TestOptions.getInstance();

        final SvnOperationFactory svnOperationFactory = new SvnOperationFactory();
        final Sandbox sandbox = Sandbox.createWithCleanup(getTestName() + ".testRemoteAddOverUnversionedFileConflictResolution", options);
        try {
            final SVNURL url = sandbox.createSvnRepository();

            final CommitBuilder commitBuilder1 = new CommitBuilder(url);
            commitBuilder1.commit();

            final CommitBuilder commitBuilder2 = new CommitBuilder(url);
            commitBuilder2.addFile("file", "their".getBytes());
            commitBuilder2.commit();

            final WorkingCopy workingCopy = sandbox.checkoutNewWorkingCopy(url, 1);
            final File workingCopyDirectory = workingCopy.getWorkingCopyDirectory();
            final File file = workingCopy.getFile("file");

            SVNFileUtil.ensureDirectoryExists(file.getParentFile());
            TestUtil.writeFileContentsString(file, "mine");

            final SvnUpdate update = svnOperationFactory.createUpdate();
            update.setSingleTarget(SvnTarget.fromFile(workingCopyDirectory));
            update.run();

            runResolve(svnOperationFactory, file, SVNConflictChoice.MERGED);

            Assert.assertEquals("mine", TestUtil.readFileContentsString(file));

            final Map<File,SvnStatus> statuses = TestUtil.getStatuses(svnOperationFactory, workingCopyDirectory);
            Assert.assertEquals(SVNStatusType.STATUS_DELETED, statuses.get(file).getNodeStatus());
            Assert.assertFalse(statuses.get(file).isConflicted());

        } finally {
            svnOperationFactory.dispose();
            sandbox.dispose();
        }
    }

    @Test
    public void testBothDeletedTreeConflict() throws Exception {
        final TestOptions options = TestOptions.getInstance();

        final SvnOperationFactory svnOperationFactory = new SvnOperationFactory();
        final Sandbox sandbox = Sandbox.createWithCleanup(getTestName() + ".testBothDeletedTreeConflict", options);
        try {
            final SVNURL url = sandbox.createSvnRepository();

            final CommitBuilder commitBuilder1 = new CommitBuilder(url);
            commitBuilder1.addFile("file");
            commitBuilder1.commit();

            final CommitBuilder commitBuilder2 = new CommitBuilder(url);
            commitBuilder2.delete("file");
            commitBuilder2.commit();

            final WorkingCopy workingCopy = sandbox.checkoutNewWorkingCopy(url, 1);
            final File file = workingCopy.getFile("file");

            workingCopy.delete(file);

            final SvnUpdate update = svnOperationFactory.createUpdate();
            update.setSingleTarget(SvnTarget.fromFile(workingCopy.getWorkingCopyDirectory()));
            update.run();

            final SvnGetInfo getInfo = svnOperationFactory.createGetInfo();
            getInfo.setSingleTarget(SvnTarget.fromFile(file));
            final SvnInfo svnInfo = getInfo.run();

            final Collection<SVNConflictDescription> conflicts = svnInfo.getWcInfo().getConflicts();
            Assert.assertEquals(1, conflicts.size());

            final SVNTreeConflictDescription conflict = (SVNTreeConflictDescription)conflicts.iterator().next();
            Assert.assertEquals(SVNConflictAction.DELETE, conflict.getConflictAction());
            Assert.assertEquals(SVNConflictReason.DELETED, conflict.getConflictReason());
            Assert.assertEquals(url, conflict.getSourceLeftVersion().getRepositoryRoot());
            Assert.assertEquals(url, conflict.getSourceRightVersion().getRepositoryRoot());

        } finally {
            svnOperationFactory.dispose();
            sandbox.dispose();
        }
    }

    private void runResolve(SvnOperationFactory svnOperationFactory, File file, SVNConflictChoice resolution) throws SVNException {
        final SVNClientManager clientManager = SVNClientManager.newInstance(svnOperationFactory.getOptions(), svnOperationFactory.getRepositoryPool());
        try {
            final SVNWCClient wcClient = clientManager.getWCClient();
            wcClient.doResolve(file, SVNDepth.INFINITY, resolution);
        } finally {
            clientManager.dispose();
        }
    }

    private String getTestName() {
        return "MergeTest";
    }
}