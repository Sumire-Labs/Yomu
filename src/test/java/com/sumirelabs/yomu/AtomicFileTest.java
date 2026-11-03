package com.sumirelabs.yomu;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class AtomicFileTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    private Path root(){return folder.getRoot().toPath();}
    private Path original() throws IOException {
        Path target=root().resolve("config.json5");Files.write(target,"original".getBytes(StandardCharsets.UTF_8));return target;
    }
    private String text(Path path) throws IOException {return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);}
    private void noTemporaryFiles() throws IOException {
        try(DirectoryStream<Path> paths=Files.newDirectoryStream(root(),".yomu-*.tmp")) {assertFalse(paths.iterator().hasNext());}
    }
    private void originalIntact(Path target) throws IOException {assertEquals("original",text(target));noTemporaryFiles();}

    @Test public void createsAndReplacesUtf8WithExactlyTheConfiguredFormat() throws Exception {
        Object value=Yomu.object(Yomu.entry("caption",Yomu.commented("説明","日本語\n\ud800")),
                Yomu.entry("values",Arrays.asList(1L,new java.math.BigDecimal("0.125"),-0.0)));
        Path target=original();
        for(boolean strict:new boolean[]{false,true}) {
            Json5Writer writer=new Json5Writer().withIndent("  ").withQuoteKeys(false).withStrictJson(strict);
            writer.writeAtomicallyTo(target,value);
            assertEquals(writer.stringify(value),text(target));
            Yomu.read(target,Json5ReadOptions.DEFAULT.withStrictJson(strict));noTemporaryFiles();
        }
        Path created=root().resolve("new.json5");Yomu.writeAtomicallyTo(created,null);
        assertEquals("null",text(created));noTemporaryFiles();
    }

    @Test public void validationAndIteratorFailuresDoNotTouchTheTarget() throws Exception {
        Path target=original();Json5Writer strict=new Json5Writer().withStrictJson(true);
        List<Object> cycle=new ArrayList<Object>();cycle.add(cycle);
        for(Object bad:Arrays.asList(Arrays.asList(1,Double.NaN),new Object(),cycle)) {
            try {strict.writeAtomicallyTo(target,bad);fail("Invalid value accepted");}catch(IllegalArgumentException expected){}
            originalIntact(target);
        }
        Iterable<Object> broken=() -> {throw new IllegalStateException("iterator failed");};
        try {new Json5Writer().writeAtomicallyTo(target,broken);fail("Iterator failure lost");}catch(IllegalStateException expected){}
        originalIntact(target);
        Path missing=root().resolve("missing.json5");
        try {strict.writeAtomicallyTo(missing,Double.POSITIVE_INFINITY);fail();}catch(IllegalArgumentException expected){}
        assertFalse(Files.exists(missing));noTemporaryFiles();
    }

    @Test public void partialTemporaryWriteIsDiscardedBeforeCommit() throws Exception {
        Path target=original();IOException expected=new IOException("write failed");Path[] staged={null};
        try {
            AtomicFiles.replace(target,temp -> {
                staged[0]=temp;assertEquals(target.toAbsolutePath().getParent(),temp.getParent());
                Files.write(temp,"partial".getBytes(StandardCharsets.UTF_8));assertEquals("original",text(target));throw expected;
            },(temp,destination,options) -> fail("Commit reached after write failure"));fail();
        }catch(IOException failure){assertSame(expected,failure);}
        assertFalse(Files.exists(staged[0]));originalIntact(target);
    }

    @Test public void temporaryCloseFailurePreventsCommit() throws Exception {
        Path target=original();IOException expected=new IOException("close failed");
        try {
            AtomicFiles.replace(target,temp -> {
                try(OutputStream out=new FilterOutputStream(Files.newOutputStream(temp)) {
                    public void close() throws IOException {super.close();throw expected;}
                }) {out.write("complete".getBytes(StandardCharsets.UTF_8));}
            },(temp,destination,options) -> fail("Commit reached after close failure"));fail();
        }catch(IOException failure){assertSame(expected,failure);}
        originalIntact(target);
    }

    @Test public void commitFailuresNeverFallBackToAnOverwrite() throws Exception {
        Path target=original();
        for(IOException expected:Arrays.asList(new AtomicMoveNotSupportedException("temporary","target","test provider"),new IOException("move failed"))) {
            int[] moves={0};Path[] staged={null};
            try {
                AtomicFiles.replace(target,temp -> {staged[0]=temp;Files.write(temp,"complete".getBytes(StandardCharsets.UTF_8));},
                        (temp,destination,options) -> {moves[0]++;assertArrayEquals(new CopyOption[]{StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING},options);assertEquals("complete",text(temp));assertEquals("original",text(destination));throw expected;});fail();
            }catch(IOException failure){assertSame(expected,failure);}
            assertEquals(1,moves[0]);assertFalse(Files.exists(staged[0]));originalIntact(target);
        }
    }

    @Test public void missingTargetStaysMissingWhenCommitFails() throws Exception {
        Path target=root().resolve("new.json5");
        try {
            AtomicFiles.replace(target,temp -> Files.write(temp,new byte[]{1}),
                    (temp,destination,options) -> {throw new AtomicMoveNotSupportedException(temp.toString(),destination.toString(),"test");});fail();
        }catch(AtomicMoveNotSupportedException expected){}
        assertFalse(Files.exists(target));noTemporaryFiles();
    }

    @Test public void cleanupFailureIsSuppressedWithoutReplacingTheOriginalError() throws Exception {
        Path target=original();IOException expected=new IOException("primary failure");Path[] staged={null};
        try {
            try {
                AtomicFiles.replace(target,temp -> {
                    staged[0]=temp;Files.delete(temp);Files.createDirectory(temp);Files.write(temp.resolve("child"),new byte[]{1});throw expected;
                },(temp,destination,options) -> fail("Commit reached"));fail();
            }catch(IOException failure){assertSame(expected,failure);assertEquals(1,failure.getSuppressed().length);assertTrue(failure.getSuppressed()[0] instanceof IOException);}
            assertEquals("original",text(target));
        }finally{if(staged[0]!=null){Files.deleteIfExists(staged[0].resolve("child"));Files.deleteIfExists(staged[0]);}}
        noTemporaryFiles();
    }

    @Test public void directoriesAndMissingParentsAreNotReplacedOrCreated() throws Exception {
        Path directory=Files.createDirectory(root().resolve("directory"));Path marker=directory.resolve("keep");Files.write(marker,new byte[]{42});
        try {Yomu.writeAtomicallyTo(directory,true);fail();}catch(IOException expected){}
        assertArrayEquals(new byte[]{42},Files.readAllBytes(marker));
        Path parent=root().resolve("absent");
        try {Yomu.writeAtomicallyTo(parent.resolve("config.json5"),true);fail();}catch(IOException expected){}
        assertFalse(Files.exists(parent));noTemporaryFiles();
    }

    @Test public void symbolicLinksIncludingDanglingLinksAreRejected() throws Exception {
        Path target=original(),link=root().resolve("link.json5");
        try {Files.createSymbolicLink(link,target.getFileName());}
        catch(IOException | UnsupportedOperationException | SecurityException unavailable){Assume.assumeNoException(unavailable);}
        try {Yomu.writeAtomicallyTo(link,true);fail();}catch(IOException expected){}
        assertTrue(Files.isSymbolicLink(link));originalIntact(target);
        Files.delete(target);
        try {Yomu.writeAtomicallyTo(link,true);fail();}catch(IOException expected){}
        assertTrue(Files.isSymbolicLink(link));assertFalse(Files.exists(target));noTemporaryFiles();
    }

    @Test public void existingPosixPermissionsArePreservedWhenAvailable() throws Exception {
        Path target=original();Assume.assumeNotNull(Files.getFileAttributeView(target,PosixFileAttributeView.class));
        Set<PosixFilePermission> expected=EnumSet.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.GROUP_READ);
        Files.setPosixFilePermissions(target,expected);Yomu.writeAtomicallyTo(target,true);
        assertEquals(expected,Files.getPosixFilePermissions(target));noTemporaryFiles();
    }

    @Test public void existingAclIsPreservedWhenAvailable() throws Exception {
        Path target=original();AclFileAttributeView view=Files.getFileAttributeView(target,AclFileAttributeView.class);Assume.assumeNotNull(view);
        List<AclEntry> expected=view.getAcl();Yomu.writeAtomicallyTo(target,true);
        assertEquals(expected,Files.getFileAttributeView(target,AclFileAttributeView.class).getAcl());noTemporaryFiles();
    }

    @Test public void nonWritableTargetIsNotReplaced() throws Exception {
        Path target=original();DosFileAttributeView dos=Files.getFileAttributeView(target,DosFileAttributeView.class);
        Assume.assumeNotNull(dos);
        try {
            dos.setReadOnly(true);Assume.assumeFalse(Files.isWritable(target));
            try {Yomu.writeAtomicallyTo(target,true);fail("Read-only target replaced");}catch(AccessDeniedException expected){}
            originalIntact(target);
        }finally{dos.setReadOnly(false);}
    }

    @Test public void concurrentSavesExposeOnlyCompleteDocuments() throws Exception {
        Path target=root().resolve("config.json5");Yomu.writeAtomicallyTo(target,Collections.singletonMap("payload","initial"));
        ExecutorService pool=Executors.newFixedThreadPool(4);CountDownLatch start=new CountDownLatch(1);List<Future<Void>> writes=new ArrayList<Future<Void>>();
        try {
            for(int id=0;id<4;id++) {final int writer=id;
                writes.add(pool.submit(() -> {start.await();char[] chars=new char[4096+writer];Arrays.fill(chars,(char)('a'+writer));
                    for(int i=0;i<4;i++) {
                        try {Yomu.writeAtomicallyTo(target,Collections.singletonMap("payload",new String(chars)));}
                        catch(AccessDeniedException sharingViolation) { /* Windows may reject a concurrent rename; no fallback is allowed. */ }
                    }return null;}));
            }
            start.countDown();
            for(int i=0;i<100;i++) {
                try {Object value=Yomu.read(target);assertTrue(value instanceof Map);assertTrue(((Map<?,?>)value).get("payload") instanceof String);}
                catch(AccessDeniedException sharingViolation) { /* An open may also be denied during replacement. */ }
                Thread.yield();
            }
            for(Future<Void> write:writes)write.get(30,TimeUnit.SECONDS);
            Yomu.read(target);noTemporaryFiles();
            Yomu.writeAtomicallyTo(target,Collections.singletonMap("payload","final"));
            assertEquals("final",Yomu.readObject(target).getString("payload"));noTemporaryFiles();
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(30,TimeUnit.SECONDS));}
    }
}
