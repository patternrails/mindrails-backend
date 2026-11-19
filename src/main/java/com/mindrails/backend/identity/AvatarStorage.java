package com.mindrails.backend.identity;

import com.mindrails.backend.common.config.FileStorageProperties;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public final class AvatarStorage {
    public static final long MAX_BYTES=2*1024*1024;
    private final Path directory;
    public AvatarStorage(FileStorageProperties files) { directory = files.avatars(); }
    public String save(MultipartFile file) throws IOException {
        if(file.isEmpty() || file.getSize()>MAX_BYTES) throw new IdentityException(400,"Avatar must be a PNG or JPEG of at most 2 MiB");
        try(var input=ImageIO.createImageInputStream(file.getInputStream())) {
            var readers=ImageIO.getImageReaders(input);
            if(!readers.hasNext()) throw new IdentityException(400,"Avatar must be a PNG or JPEG image");
            var reader=readers.next();
            try {
                reader.setInput(input,true,true);
                if(!Set.of("png","jpeg","jpg").contains(reader.getFormatName().toLowerCase(Locale.ROOT))) throw new IdentityException(400,"Avatar must be a PNG or JPEG image");
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<1 || height<1 || width>2048 || height>2048) throw new IdentityException(400,"Avatar dimensions must not exceed 2048 × 2048");
                var image=reader.read(0);
                String filename=UUID.randomUUID()+".png";
                Files.createDirectories(directory);
                try(var output=Files.newOutputStream(directory.resolve(filename),StandardOpenOption.CREATE_NEW)) { ImageIO.write(image,"png",output); }
                return "/api/users/avatars/"+filename;
            } finally { reader.dispose(); }
        } catch(javax.imageio.IIOException | IllegalArgumentException e) { throw new IdentityException(400,"Invalid avatar image"); }
    }
    public byte[] read(String filename) throws IOException {
        if(!filename.matches("[0-9a-f-]{36}\\.png")) throw new IdentityException(404,"Avatar not found");
        Path file=directory.resolve(filename);
        if(!Files.isRegularFile(file)) throw new IdentityException(404,"Avatar not found");
        return Files.readAllBytes(file);
    }
    public void discard(String url) {
        if (url == null || !url.matches("/api/users/avatars/[0-9a-f-]{36}\\.png")) return;
        try { Files.deleteIfExists(directory.resolve(url.substring(url.lastIndexOf('/')+1))); }
        catch (IOException e) { org.slf4j.LoggerFactory.getLogger(AvatarStorage.class).warn("Unable to remove an unused avatar",e); }
    }
}
