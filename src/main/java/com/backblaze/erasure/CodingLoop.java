/**
 * Interface for a method of looping over inputs and encoding them.
 *
 * Copyright 2015, Backblaze, Inc.  All rights reserved.
 */

package com.backblaze.erasure;

// CodingLoop 是一个策略接口。它的核心作用是定义纠删码核心计算循环的标准。
// 在里德-所罗门算法中，最耗时的部分是矩阵乘法（在伽罗华域 $GF(2^8)$ 上）。
// 为了优化性能，开发者实现了多种不同的循环嵌套方式和乘法计算方法。CodingLoop 接口将这些不同的实现方式抽象化，使得系统可以根据硬件环境（如 CPU 缓存大小、指令集等）选择最高效的实现。
// 简单来说，它决定了程序是先遍历“字节”，还是先遍历“输入分片”，还是先遍历“输出分片”，以此来压榨 CPU 的性能。
public interface CodingLoop {

    /**
     * All of the available coding loop algorithms.
     *
     * The different choices nest the three loops in different orders,
     * and either use the log/exponents tables, or use the multiplication
     * table.
     *
     * The naming of the three loops is (with number of loops in benchmark):
     *
     *    "byte"   - Index of byte within shard.  (200,000 bytes in each shard)
     *
     *    "input"  - Which input shard is being read.  (17 data shards)
     *
     *    "output"  - Which output shard is being computed.  (3 parity shards)
     *
     * And the naming for multiplication method is:
     *
     *    "table"  - Use the multiplication table.
     *
     *    "exp"    - Use the logarithm/exponent table.
     *
     * The ReedSolomonBenchmark class compares the performance of the different
     * loops, which will depend on the specific processor you're running on.
     *
     * This is the inner loop.  It needs to be fast.  Be careful
     * if you change it.
     *
     * I have tried inlining Galois.multiply(), but it doesn't
     * make things any faster.  The JIT compiler is known to inline
     * methods, so it's probably already doing so.
     */
    // 包含了所有可用算法实现的数组。
    // 举了 12 种不同的实现方案。这些命名的规则体现了它们的差异：
    // 循环顺序: 名字中的 Byte、Input、Output 的排列顺序代表了三层嵌套循环的先后顺序。例如 ByteInputOutput... 表示最外层是字节循环。
    // Exp: 使用对数/指数表（log/exponent tables）来计算乘法。
    // Table: 使用预计算的乘法表（multiplication table）来计算。
    CodingLoop[] ALL_CODING_LOOPS =
            new CodingLoop[] {
                    new ByteInputOutputExpCodingLoop(),
                    new ByteInputOutputTableCodingLoop(),
                    new ByteOutputInputExpCodingLoop(),
                    new ByteOutputInputTableCodingLoop(),
                    new InputByteOutputExpCodingLoop(),
                    new InputByteOutputTableCodingLoop(),
                    new InputOutputByteExpCodingLoop(),
                    new InputOutputByteTableCodingLoop(),
                    new OutputByteInputExpCodingLoop(),
                    new OutputByteInputTableCodingLoop(),
                    new OutputInputByteExpCodingLoop(),
                    new OutputInputByteTableCodingLoop(),
            };

    /**
     * Multiplies a subset of rows from a coding matrix by a full set of
     * input shards to produce some output shards.
     *
     * @param matrixRows The rows from the matrix to use.
     * @param inputs An array of byte arrays, each of which is one input shard.
     *               The inputs array may have extra buffers after the ones
     *               that are used.  They will be ignored.  The number of
     *               inputs used is determined by the length of the
     *               each matrix row.
     * @param inputCount The number of input byte arrays.
     * @param outputs Byte arrays where the computed shards are stored.  The
     *                outputs array may also have extra, unused, elements
     *                at the end.  The number of outputs computed, and the
     *                number of matrix rows used, is determined by
     *                outputCount.
     * @param outputCount The number of outputs to compute.
     * @param offset The index in the inputs and output of the first byte
     *               to process.
     * @param byteCount The number of bytes to process.
     */
    // 方法执行矩阵乘法，将输入数据（Input Shards）与编码矩阵（Matrix）相乘，生成输出数据（Output Shards）。
    // 参数说明:
    //
    //matrixRows: 编码矩阵的行。每一行决定了如何从输入数据组合出对应的输出数据。
    //
    //inputs: 二维字节数组，代表输入的数据分片。
    //
    //inputCount: 实际参与计算的输入分片数量。
    //
    //outputs: 二维字节数组，用于存放计算结果（如校验片或修复的数据片）。
    //
    //outputCount: 需要计算生成的输出分片数量。
    //
    //offset: 缓冲区中的起始偏移量（从哪个字节开始算）。
    //
    //byteCount: 每个分片需要处理的字节总数。
    // 实现数据冗余的核心，通过 $Matrix \times Inputs = Outputs$ 的逻辑来产生校验数据。
     void codeSomeShards(final byte [] [] matrixRows,
                         final byte [] [] inputs,
                         final int inputCount,
                         final byte [] [] outputs,
                         final int outputCount,
                         final int offset,
                         final int byteCount);

    /**
     * Multiplies a subset of rows from a coding matrix by a full set of
     * input shards to produce some output shards, and checks that the
     * the data is those shards matches what's expected.
     *
     * @param matrixRows The rows from the matrix to use.
     * @param inputs An array of byte arrays, each of which is one input shard.
     *               The inputs array may have extra buffers after the ones
     *               that are used.  They will be ignored.  The number of
     *               inputs used is determined by the length of the
     *               each matrix row.
     * @param inputCount THe number of input byte arrays.
     * @param toCheck Byte arrays where the computed shards are stored.  The
     *                outputs array may also have extra, unused, elements
     *                at the end.  The number of outputs computed, and the
     *                number of matrix rows used, is determined by
     *                outputCount.
     * @param checkCount The number of outputs to compute.
     * @param offset The index in the inputs and output of the first byte
     *               to process.
     * @param byteCount The number of bytes to process.
     * @param tempBuffer A place to store temporary results.  May be null.
     */
    // 作用是验证现有的数据分片是否与计算出来的结果一致，用于检测数据损坏。
    // 参数说明:
    //
    //matrixRows: 同上。
    //
    //inputs: 输入的数据分片。
    //
    //inputCount: 输入分片数量。
    //
    //toCheck: 需要进行校验的数据分片。
    //
    //checkCount: 需要校验的分片数量。
    //
    //offset: 起始偏移量。
    //
    //byteCount: 处理的字节数。
    //
    //tempBuffer: 临时缓冲区。如果提供，它会用来存储中间计算结果，避免重复申请内存；如果为 null，实现类可能会内部处理。
     boolean checkSomeShards(final byte [] [] matrixRows,
                             final byte [] [] inputs,
                             final int inputCount,
                             final byte [] [] toCheck,
                             final int checkCount,
                             final int offset,
                             final int byteCount,
                             final byte [] tempBuffer);
}
