/**
 * Command-line program encodes one file using Reed-Solomon 4+2.
 *
 * Copyright 2015, Backblaze, Inc.
 */

package com.backblaze.erasure;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * Command-line program encodes one file using Reed-Solomon 4+2.
 *
 * The one argument should be a file name, say "foo.txt".  This program
 * will create six files in the same directory, breaking the input file
 * into four data shards, and two parity shards.  The output files are
 * called "foo.txt.0", "foo.txt.1", ..., and "foo.txt.5".  Numbers 4
 * and 5 are the parity shards.
 *
 * The data stored is the file size (four byte int), followed by the
 * contents of the file, and then padded to a multiple of four bytes
 * with zeros.  The padding is because all four data shards must be
 * the same size.
 */
// SampleEncoder 的主要作用是将一个原始文件切分为多个数据分片（Data Shards），并生成额外的校验分片（Parity Shards）。
// 具体逻辑如下：
// 纠错配置：采用 4+2 模式（4个数据分片，2个校验分片）。
// 冗余能力：通过这种编码方式，只要这 6 个分片中的任意 4 个分片存在，就可以恢复出原始文件。即使丢失了任何 2 个分片（无论是数据还是校验分片），数据依然安全。
// 输出结果：它会生成 6 个新文件，后缀分别为 .0 到 .5。其中 .0 到 .3 是原始数据（包含长度信息），.4 和 .5 是生成的冗余数据。
public class SampleEncoder {
    // 数据分片数量。设置为 4，意味着文件将被拆分为 4 份。
    public static final int DATA_SHARDS = 4;
    // 校验分片数量。设置为 2，表示允许最多丢失 2 个分片而不丢失数据。
    public static final int PARITY_SHARDS = 2;
    // 总分片数量。等于 DATA_SHARDS + PARITY_SHARDS，即 6。
    public static final int TOTAL_SHARDS = 6;
    // 整数占用的字节数。固定为 4，用于在存储数据前记录原始文件的实际大小（以字节为单位）。
    public static final int BYTES_IN_INT = 4;

    public static void main(String [] arguments) throws IOException {

        // Parse the command line
        if (arguments.length != 1) {
            System.out.println("Usage: SampleEncoder <fileName>");
            return;
        }
        final File inputFile = new File(arguments[0]);
        if (!inputFile.exists()) {
            System.out.println("Cannot read input file: " + inputFile);
            return;
        }

        // Get the size of the input file.  (Files bigger that
        // Integer.MAX_VALUE will fail here!)
        // 获取文件字节长度。
        final int fileSize = (int) inputFile.length();

        // Figure out how big each shard will be.  The total size stored
        // will be the file size (8 bytes) plus the file.
        // 实际需要存储的大小。它是 fileSize + 4（多出的 4 字节用于存放原始文件长度，以便解码时知道在哪里截断）
        final int storedSize = fileSize + BYTES_IN_INT;
        // 计算每个分片的大小。
        // 计算公式为 (storedSize + DATA_SHARDS - 1) / DATA_SHARDS。这是一种向上取整的方法，确保 4 个分片能装下所有数据。
        final int shardSize = (storedSize + DATA_SHARDS - 1) / DATA_SHARDS;

        // Create a buffer holding the file size, followed by
        // the contents of the file.
        // 创建一个字节数组 allBytes，大小为 shardSize * DATA_SHARDS
        final int bufferSize = shardSize * DATA_SHARDS;
        final byte [] allBytes = new byte[bufferSize];
        // 使用 ByteBuffer 将 fileSize（4 字节）写入数组开头。
        ByteBuffer.wrap(allBytes).putInt(fileSize);
        InputStream in = new FileInputStream(inputFile);
        int bytesRead = in.read(allBytes, BYTES_IN_INT, fileSize);
        if (bytesRead != fileSize) {
            throw new IOException("not enough bytes read");
        }
        in.close();

        // Make the buffers to hold the shards.
        // 创建一个二维数组，第一维是 6 个分片，第二维是每个分片的具体字节数据。
        byte [] [] shards = new byte [TOTAL_SHARDS] [shardSize];

        // Fill in the data shards
        // 将 allBytes 中的数据按顺序拷贝到 shards[0] 到 shards[3] 中
        for (int i = 0; i < DATA_SHARDS; i++) {
            System.arraycopy(allBytes, i * shardSize, shards[i], 0, shardSize);
        }

        // Use Reed-Solomon to calculate the parity.
        // 实例化编码器。
        ReedSolomon reedSolomon = ReedSolomon.create(DATA_SHARDS, PARITY_SHARDS);
        // 读取 shards[0-3] 的内容，计算出冗余数据，并自动填入 shards[4] 和 shards[5]。
        reedSolomon.encodeParity(shards, 0, shardSize);

        // Write out the resulting files.
        // 遍历 shards 数组，将 6 个分片分别写入磁盘。
        for (int i = 0; i < TOTAL_SHARDS; i++) {
            File outputFile = new File(
                    inputFile.getParentFile(),
                    inputFile.getName() + "." + i);
            OutputStream out = new FileOutputStream(outputFile);
            out.write(shards[i]);
            out.close();
            System.out.println("wrote " + outputFile);
        }
    }
}
