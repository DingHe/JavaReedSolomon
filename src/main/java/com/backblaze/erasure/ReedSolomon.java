/**
 * Reed-Solomon Coding over 8-bit values.
 *
 * Copyright 2015, Backblaze, Inc.
 */

package com.backblaze.erasure;

/**
 * Reed-Solomon Coding over 8-bit values.
 */
// Reed-Solomon 纠错算法的核心类。Reed-Solomon（RS码）广泛应用于数据存储系统（如分布式文件系统、RAID），用于在部分数据丢失或损坏时进行恢复。
// ReedSolomon 类的主要作用是提供 数据冗余编码（Encoding） 和 数据恢复（Decoding） 的功能。
// 编码（Encoding）：给定 $n$ 个数据分片（Data Shards），通过矩阵运算生成 $m$ 个校验分片（Parity Shards）。
// 纠错/恢复（Decoding）：当总共 $n+m$ 个分片中出现丢失时，只要存活的分片数量大于等于 $n$，该类就能通过矩阵逆运算找回原始的 $n$ 个数据分片。
// 设计特点：它使用了基于伽罗瓦域 $GF(2^8)$ 的运算，并将编码逻辑抽象为 CodingLoop 以支持不同的性能优化（如纯 Java 实现或利用 SIMD 指令）。
public class ReedSolomon {
    // 原始数据分片的数量。例如，将一个文件切分成 10 份，则该值为 10。
    private final int dataShardCount;
    // 校验分片的数量。代表了系统允许丢失的最大分片数。
    private final int parityShardCount;
    // 总分片数。等于 dataShardCount + parityShardCount。
    private final int totalShardCount;
    // 编码矩阵。这是 RS 码的核心，大小为 totalShardCount x dataShardCount。它的前 $n$ 行通常是单位矩阵。
    private final Matrix matrix;
    // 计算循环策略。定义了具体的字节级计算逻辑（如查表法、AVX加速等），用于实际的矩阵乘法操作。
    private final CodingLoop codingLoop;

    /**
     * Rows from the matrix for encoding parity, each one as its own
     * byte array to allow for efficient access while encoding.
     */
    // 校验行缓存。从 matrix 中提取出来的专门用于计算校验片的行，为了提高编码效率，预先存储为二维字节数组。
    private final byte [] [] parityRows;

    /**
     * Creates a ReedSolomon codec with the default coding loop.
     */
    public static ReedSolomon create(int dataShardCount, int parityShardCount) {
        return new ReedSolomon(dataShardCount, parityShardCount, new InputOutputByteTableCodingLoop());
    }

    /**
     * Initializes a new encoder/decoder, with a chosen coding loop.
     */
    public ReedSolomon(int dataShardCount, int parityShardCount, CodingLoop codingLoop) {

        // We can have at most 256 shards total, as any more would
        // lead to duplicate rows in the Vandermonde matrix, which
        // would then lead to duplicate rows in the built matrix
        // below. Then any subset of the rows containing the duplicate
        // rows would be singular.
        // 确保数据分片和校验分片的总数不超过 256。
        if (256 < dataShardCount + parityShardCount) {
            throw new IllegalArgumentException("too many shards - max is 256");
        }

        this.dataShardCount = dataShardCount;
        this.parityShardCount = parityShardCount;
        this.codingLoop = codingLoop;
        this.totalShardCount = dataShardCount + parityShardCount;
        // 此时 matrix 的上半部分是一个单位矩阵，下半部分是用于生成校验数据的生成矩阵。
        matrix = buildMatrix(dataShardCount, this.totalShardCount);
        // 初始化一个二维字节数组，用来存放矩阵中专门负责计算校验位的那些行。
        parityRows = new byte [parityShardCount] [];
        for (int i = 0; i < parityShardCount; i++) {
            parityRows[i] = matrix.getRow(dataShardCount + i);
        }
    }

    /**
     * Returns the number of data shards.
     */
    public int getDataShardCount() {
        return dataShardCount;
    }

    /**
     * Returns the number of parity shards.
     */
    public int getParityShardCount() {
        return parityShardCount;
    }

    /**
     * Returns the total number of shards.
     */
    public int getTotalShardCount() {
        return totalShardCount;
    }

    /**
     * Encodes parity for a set of data shards.
     *
     * @param shards An array containing data shards followed by parity shards.
     *               Each shard is a byte array, and they must all be the same
     *               size.
     * @param offset The index of the first byte in each shard to encode.
     * @param byteCount The number of bytes to encode in each shard.
     *
     */
    // 根据已有的数据分片（Data Shards）计算出对应的校验分片（Parity Shards）。
    // offset 和 byteCount 允许你对每个分片数组（byte[]）进行局部处理，而不是强制从头到尾处理整个数组。
    // 在实际开发中，这两个参数非常重要，原因有三：
    //复用缓冲区：
    //比如你分配了一个 1MB 的数组，但实际接收到的数据只有 500KB。你可以设置 offset = 0, byteCount = 500 * 1024，而不必为了裁剪数据去创建一个新的数组，节省了内存分配开销。
    // 分块处理：
    //如果一个分片非常大（比如 1GB），你可以通过改变 offset 每次处理一小段（比如每次处理 4KB），循环进行编码，这在处理大文件时能避免一次性占用过多内存。
    // 跳过头部信息：
    //有时候你的 shards 数组里前面存了一些元数据（如文件头、校验和），真正的负载数据（Payload）是从第 16 个字节开始的。此时你可以设置 offset = 16，算法就会自动忽略掉前面的 16 个字节。
    public void encodeParity(byte[][] shards, int offset, int byteCount) {
        // Check arguments.
        checkBuffersAndSizes(shards, offset, byteCount);

        // Build the array of output buffers.
        // 创建一个新的二维数组 outputs，大小等于校验分片的数量。
        byte [] [] outputs = new byte [parityShardCount] [];
        // 将 shards 数组中存放校验分片的部分（即下标从 dataShardCount 开始的部分）的引用拷贝到 outputs 数组中。
        System.arraycopy(shards, dataShardCount, outputs, 0, parityShardCount);

        // Do the coding.
        // 调用了具体的 codingLoop 实现来完成矩阵运算
        codingLoop.codeSomeShards(
                parityRows, // 这是在构造函数里预生成的生成矩阵的下半部分。每一行代表一个校验分片的生成规则。
                shards,
            dataShardCount, // 计算逻辑只会读取 shards 的前 dataShardCount 个元素（即原始数据）。
                outputs,
            parityShardCount, // 作为输出。计算逻辑会将结果写入到这 parityShardCount 个校验缓冲区中。
                offset,
            byteCount); // offset 和 byteCount 允许你对每个分片数组（byte[]）进行局部处理，而不是强制从头到尾处理整个数组。
    }

    /**
     * Returns true if the parity shards contain the right data.
     *
     * @param shards An array containing data shards followed by parity shards.
     *               Each shard is a byte array, and they must all be the same
     *               size.
     * @param firstByte The index of the first byte in each shard to check.
     * @param byteCount The number of bytes to check in each shard.
     */
    // 验证数据的一致性。它通过重新计算校验分片并与现有的校验分片进行比对，来判断数据是否损坏。
    // 确保传入的二维数组 shards 包含正确数量的分片，每个分片长度一致，且 firstByte（即前面讨论过的 offset）和 byteCount 指定的区间在数组范围内。
    public boolean isParityCorrect(byte[][] shards, int firstByte, int byteCount) {
        // Check arguments.
        checkBuffersAndSizes(shards, firstByte, byteCount);

        // Build the array of buffers being checked.
        byte [] [] toCheck = new byte [parityShardCount] [];
        System.arraycopy(shards, dataShardCount, toCheck, 0, parityShardCount);

        // Do the checking.
        return codingLoop.checkSomeShards(
                parityRows,
                shards, dataShardCount,
                toCheck, parityShardCount,
                firstByte, byteCount,
                null);
    }

    /**
     * Returns true if the parity shards contain the right data.
     *
     * This method may be significantly faster than the one above that does
     * not use a temporary buffer.
     *
     * @param shards An array containing data shards followed by parity shards.
     *               Each shard is a byte array, and they must all be the same
     *               size.
     * @param firstByte The index of the first byte in each shard to check.
     * @param byteCount The number of bytes to check in each shard.
     * @param tempBuffer A temporary buffer (the same size as each of the
     *                   shards) to use when computing parity.
     */
    // isParityCorrect 方法的高性能重载版本。与前一个版本的区别在于它引入了一个 tempBuffer（临时缓冲区）。
    // 在 Reed-Solomon 算法中，校验数据是否正确需要通过数据分片重新计算出“期望的校验值”。
    // 如果没有临时缓冲区，计算引擎可能需要频繁地在堆内存中创建和销毁临时数组，这会产生大量的 GC（垃圾回收）压力。通过传入一个预先分配好的 tempBuffer，可以极大地提升在高并发或大数据量场景下的性能
    public boolean isParityCorrect(byte[][] shards, int firstByte, int byteCount, byte [] tempBuffer) {
        // Check arguments.
        checkBuffersAndSizes(shards, firstByte, byteCount);
        if (tempBuffer.length < firstByte + byteCount) {
            throw new IllegalArgumentException("tempBuffer is not big enough");
        }

        // Build the array of buffers being checked.
        byte [] [] toCheck = new byte [parityShardCount] [];
        System.arraycopy(shards, dataShardCount, toCheck, 0, parityShardCount);

        // Do the checking.
        return codingLoop.checkSomeShards(
                parityRows,
                shards, dataShardCount,
                toCheck, parityShardCount,
                firstByte, byteCount,
                tempBuffer);
    }

    /**
     * Given a list of shards, some of which contain data, fills in the
     * ones that don't have data.
     *
     * Quickly does nothing if all of the shards are present.
     *
     * If any shards are missing (based on the flags in shardsPresent),
     * the data in those shards is recomputed and filled in.
     */
    // 利用残余的数据分片和校验分片，反向推导并找回丢失的所有原始数据。
    public void decodeMissing(byte [] [] shards,
                              boolean [] shardPresent,
                              final int offset,
                              final int byteCount) {
        // Check arguments.
        checkBuffersAndSizes(shards, offset, byteCount);

        // Quick check: are all of the shards present?  If so, there's
        // nothing to do.
        // 遍历 shardPresent 布尔数组，统计当前有多少个分片是可用的。
        int numberPresent = 0;
        for (int i = 0; i < totalShardCount; i++) {
            if (shardPresent[i]) {
                numberPresent += 1;
            }
        }
        // 如果所有分片都齐了（numberPresent == totalShardCount），直接结束，不浪费计算资源。
        if (numberPresent == totalShardCount) {
            // Cool.  All of the shards data data.  We don't
            // need to do anything.
            return;
        }

        // More complete sanity check
        // 如果存活的分片数小于原始数据分片数（dataShardCount），数学上已无法恢复，抛出异常。
        if (numberPresent < dataShardCount) {
            throw new IllegalArgumentException("Not enough shards present");
        }

        // Pull out the rows of the matrix that correspond to the
        // shards that we have and build a square matrix.  This
        // matrix could be used to generate the shards that we have
        // from the original data.
        //
        // Also, pull out an array holding just the shards that
        // correspond to the rows of the submatrix.  These shards
        // will be the input to the decoding process that re-creates
        // the missing data shards.
        // 从原本 $N+M$ 行的编码矩阵中，挑选出存活的那 $N$ 行，组成一个新的 $N \times N$ 方阵。
        // 原始数据的编码公式是 $Matrix \times Data = Shards$。现在我们有了部分 $Shards$ 和对应的部分 $Matrix$，只要这部分矩阵可逆，就能求出 $Data$。
        // 存储挑选出来的矩阵行。
        Matrix subMatrix = new Matrix(dataShardCount, dataShardCount);
        // 存储对应的存活分片数据。
        byte [] [] subShards = new byte [dataShardCount] [];
        {
            int subMatrixRow = 0;
            for (int matrixRow = 0; matrixRow < totalShardCount && subMatrixRow < dataShardCount; matrixRow++) {
                if (shardPresent[matrixRow]) {
                    for (int c = 0; c < dataShardCount; c++) {
                        subMatrix.set(subMatrixRow, c, matrix.get(matrixRow, c));
                    }
                    subShards[subMatrixRow] = shards[matrixRow];
                    subMatrixRow += 1;
                }
            }
        }

        // Invert the matrix, so we can go from the encoded shards
        // back to the original data.  Then pull out the row that
        // generates the shard that we want to decode.  Note that
        // since this matrix maps back to the orginal data, it can
        // be used to create a data shard, but not a parity shard.
        // 计算子矩阵的逆矩阵。
        // 如果原矩阵是把“数据”变“分片”，那么逆矩阵就是把“分片”变回“原始数据”。
        Matrix dataDecodeMatrix = subMatrix.invert();

        // Re-create any data shards that were missing.
        //
        // The input to the coding is all of the shards we actually
        // have, and the output is the missing data shards.  The computation
        // is done using the special decode matrix we just built.
        byte [] [] outputs = new byte [parityShardCount] [];
        byte [] [] matrixRows = new byte [parityShardCount] [];
        int outputCount = 0;
        for (int iShard = 0; iShard < dataShardCount; iShard++) {
            if (!shardPresent[iShard]) {
                outputs[outputCount] = shards[iShard];
                matrixRows[outputCount] = dataDecodeMatrix.getRow(iShard);
                outputCount += 1;
            }
        }
        codingLoop.codeSomeShards(
                matrixRows,
                subShards, dataShardCount,
                outputs, outputCount,
                offset, byteCount);

        // Now that we have all of the data shards intact, we can
        // compute any of the parity that is missing.
        //
        // The input to the coding is ALL of the data shards, including
        // any that we just calculated.  The output is whichever of the
        // data shards were missing.
        outputCount = 0;
        for (int iShard = dataShardCount; iShard < totalShardCount; iShard++) {
            if (!shardPresent[iShard]) {
                outputs[outputCount] = shards[iShard];
                matrixRows[outputCount] = parityRows[iShard - dataShardCount];
                outputCount += 1;
            }
        }
        codingLoop.codeSomeShards(
                matrixRows,
                shards, dataShardCount,
                outputs, outputCount,
                offset, byteCount);
    }

    /**
     * Checks the consistency of arguments passed to public methods.
     */
    // 执行复杂的数学运算之前，确保传入的所有参数（分片数组、偏移量、字节长度）在逻辑上是合法且一致的。这可以防止在运算中途出现 ArrayIndexOutOfBoundsException（数组越界）等崩溃。
    private void checkBuffersAndSizes(byte [] [] shards, int offset, int byteCount) {
        // The number of buffers should be equal to the number of
        // data shards plus the number of parity shards.
        // 检查传入的二维数组 shards 包含的子数组数量是否正确。
        // 在 Reed-Solomon 中，分片总数必须固定为“数据分片 + 校验分片”。
        // 如果传入的数量不等于初始化时确定的 totalShardCount，计算将无法进行。
        if (shards.length != totalShardCount) {
            throw new IllegalArgumentException("wrong number of shards: " + shards.length);
        }

        // All of the shard buffers should be the same length.
        // 验证所有分片长度一致
        // 确保每一个分片（即每一个 byte[]）的大小完全相同。
        int shardLength = shards[0].length;
        for (int i = 1; i < shards.length; i++) {
            if (shards[i].length != shardLength) {
                throw new IllegalArgumentException("Shards are different sizes");
            }
        }

        // The offset and byteCount must be non-negative and fit in the buffers.
        // 偏移量 offset（起始位置）和 byteCount（处理长度）不能是负数，这符合 Java 处理数组的一般规范。
        if (offset < 0) {
            throw new IllegalArgumentException("offset is negative: " + offset);
        }
        if (byteCount < 0) {
            throw new IllegalArgumentException("byteCount is negative: " + byteCount);
        }
        if (shardLength < offset + byteCount) {
            throw new IllegalArgumentException("buffers to small: " + byteCount + offset);
        }
    }

    /**
     * Create the matrix to use for encoding, given the number of
     * data shards and the number of total shards.
     *
     * The top square of the matrix is guaranteed to be an identity
     * matrix, which means that the data shards are unchanged after
     * encoding.
     */
    // 生成一个特定的编码矩阵，使得原始数据在编码后能够保持不变（即“系统码”特性），同时保证任何分片丢失都能通过数学逆运算恢复。
    private static Matrix buildMatrix(int dataShards, int totalShards) {
        // Start with a Vandermonde matrix.  This matrix would work,
        // in theory, but doesn't have the property that the data
        // shards are unchanged after encoding.
        // 创建一个 $totalShards \times dataShards$（总分片数 $\times$ 数据分片数）的范德蒙矩阵
        // 范德蒙矩阵的特点是其任意 $n \times n$ 的子矩阵都是可逆的（非奇异的）。这意味着只要你有任意 $n$ 个分片，理论上都能找回原始数据。
        // 局限性：直接使用范德蒙矩阵进行编码，会导致输出的所有分片（包括数据分片）都变成了经过编码后的“乱码”，不符合我们“前 $n$ 个分片是原始数据”的直观需求。
        Matrix vandermonde = vandermonde(totalShards, dataShards);

        // Multiple by the inverse of the top square of the matrix.
        // This will make the top square be the identity matrix, but
        // preserve the property that any square subset of rows is
        // invertible.
        // 从范德蒙矩阵中截取最上面的 $dataShards \times dataShards$ 部分
        // 目的：为了将这个顶部方阵转化为单位矩阵（Identity Matrix）。在矩阵运算中，单位矩阵乘以原始数据等于数据本身。
        Matrix top = vandermonde.submatrix(0, 0, dataShards, dataShards);
        // 计算顶部方阵的逆矩阵 $Top^{-1}$。
        // 根据线性代数，一个矩阵乘以自身的逆矩阵等于单位矩阵：$Top \times Top^{-1} = I$
        // 将整个范德蒙矩阵乘以刚才求得的逆矩阵。
        // 上半部分：变成了 $Top \times Top^{-1} = I$（单位矩阵）。这意味着前 $n$ 个分片编码后依然是原始数据。
        // 下半部分：变成了 $Parity \times Top^{-1}$。这部分矩阵专门用于生成校验分片。
        return vandermonde.times(top.invert());
    }

    /**
     * Create a Vandermonde matrix, which is guaranteed to have the
     * property that any subset of rows that forms a square matrix
     * is invertible.
     *
     * @param rows Number of rows in the result.
     * @param cols Number of columns in the result.
     * @return A Matrix.
     */
    // 构建一个基础的范德蒙矩阵（Vandermonde Matrix）
    // 在纠错码理论中，这种矩阵的神奇之处在于：从该矩阵中任意挑选 $n$ 行组成的方阵，在数学上都是可逆的。这是 Reed-Solomon 算法能够从部分丢失的数据中恢复出原始数据的基石。
    // 范德蒙矩阵（Vandermonde Matrix） 的数学定义就是：每一行都是一个几何级数（等比数列）。
    // 之所以费力气在代码里构造这样一个矩阵，是因为它有一个极其重要的特性：任意子方阵都可逆（Invertible）。
    // 纠错应用：在 Reed-Solomon 中，丢失分片等同于从矩阵中删掉几行。剩下的行组成的矩阵依然是一个类范德蒙矩阵，依然可逆。只要可逆，就能通过矩阵求逆运算找回丢失的数据。
    private static Matrix vandermonde(int rows, int cols) {
        // 根据传入的参数，创建一个行数为 rows（总分片数 $n+m$），列数为 cols（原始数据分片数 $n$）的新矩阵实例。
        Matrix result = new Matrix(rows, cols);
        // 通过双重循环，依次确定矩阵中每一个坐标点 $(r, c)$ 的值。
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                // 计算 $r^c$。
                // 逻辑：
                // 当 $c=0$ 时，$r^0 = 1$（矩阵的第一列全是 1）。
                // 当 $c=1$ 时，$r^1 = r$（第二列是行号的字节值）。
                // 当 $c=2$ 时，$r^2 = r \cdot r$（在伽罗瓦域内的乘法）。
                result.set(r, c, Galois.exp((byte) r, c));
            }
        }
        return result;
    }
}
