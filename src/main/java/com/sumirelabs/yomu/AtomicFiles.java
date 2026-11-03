package com.sumirelabs.yomu;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** A sibling temporary file is the only file opened for writing. No non-atomic fallback. */
final class AtomicFiles {
    private AtomicFiles() { }

    // Per-call seams let failure tests exercise preparation and commit independently.
    interface TempWriter { void write(Path temporary) throws IOException; }
    interface Mover { void move(Path temporary,Path target,CopyOption... options) throws IOException; }

    static void writeUtf8(Path output,String text) throws IOException {
        final byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        replace(output,temporary -> {
            try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer data=ByteBuffer.wrap(bytes);
                while(data.hasRemaining())channel.write(data);
                channel.force(true);
            }
        },Files::move);
    }

    static void replace(Path output,TempWriter writer,Mover mover) throws IOException {
        Objects.requireNonNull(output,"output");
        Objects.requireNonNull(writer,"writer");
        Objects.requireNonNull(mover,"mover");
        // Do not normalize '..': doing so can change the meaning of paths through symlinked parents.
        Path target=output.toAbsolutePath(),parent=target.getParent();
        if(parent==null||target.getFileName()==null)throw new FileSystemException(target.toString(),null,"A file path is required");
        Access access=access(target);
        Path temporary=Files.createTempFile(parent,".yomu-",".tmp");
        try {
            access.apply(temporary);
            writer.write(temporary); // Includes force and successful close before committing.
            regularOrMissing(target);
            mover.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            // There must be no fallible work after a successful commit.
        } catch(IOException | RuntimeException | Error failure) {
            try {Files.deleteIfExists(temporary);}
            catch(IOException | RuntimeException | Error cleanup) {
                if(cleanup!=failure)failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static boolean regularOrMissing(Path target) throws IOException {
        BasicFileAttributes attributes;
        try {attributes=Files.readAttributes(target,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}
        catch(NoSuchFileException missing){return false;}
        if(!attributes.isRegularFile())throw new FileSystemException(target.toString(),null,
                "Atomic output requires a regular file; directories and symbolic links are not followed");
        if(!Files.isWritable(target))throw new AccessDeniedException(target.toString(),null,"Target is not writable");
        return true;
    }

    private static Access access(Path target) throws IOException {
        if(!regularOrMissing(target))return new Access(null,null);
        PosixFileAttributeView posix=Files.getFileAttributeView(target,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        AclFileAttributeView acl=Files.getFileAttributeView(target,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        Set<PosixFilePermission> permissions=posix==null?null:new HashSet<PosixFilePermission>(posix.readAttributes().permissions());
        List<AclEntry> entries=acl==null?null:new ArrayList<AclEntry>(acl.getAcl());
        return new Access(permissions,entries);
    }

    private static final class Access {
        final Set<PosixFilePermission> permissions;
        final List<AclEntry> entries;
        Access(Set<PosixFilePermission> permissions,List<AclEntry> entries) {
            this.permissions=permissions;this.entries=entries;
        }
        void apply(Path temporary) throws IOException {
            if(permissions!=null)Files.setPosixFilePermissions(temporary,permissions);
            if(entries!=null) {
                AclFileAttributeView acl=Files.getFileAttributeView(temporary,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
                if(acl==null)throw new FileSystemException(temporary.toString(),null,"Cannot preserve the target ACL");
                acl.setAcl(entries);
            }
        }
    }
}
