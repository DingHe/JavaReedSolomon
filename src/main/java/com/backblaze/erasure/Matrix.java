/**
 * Matrix Algebra over an 8-bit Galois Field
 *
 * Copyright 2015, Backblaze, Inc.
 */

package com.backblaze.erasure;

import java.util.Arrays;

/**
 * A matrix over the 8-bit Galois field.
 *
 * This class is not performance-critical, so the implementations
 * are simple and straightforward.
 */
// Matrix 类的主要作用是提供有限域上的矩阵运算能力。在里德-所罗门编码（Reed-Solomon）中，编码和解码本质上是矩阵与向量的乘法过程：
// 编码：数据向量乘以“生成矩阵”得到校验分片。
// 解码：当分片丢失时，通过构造生成矩阵的逆矩阵（Inverse Matrix）来找回原始数据。
// 该类通过封装二维字节数组，实现了矩阵的乘法、求逆、增广以及高斯消元等关键算法。由于定义在 $GF(2^8)$ 上，所有的加减法都变成了**异或（XOR）**运算，而乘除法则是通过 Galois 工具类实现的。
public class Matrix {

    /**
     * The number of rows in the matrix.
     */
    // 行数。矩阵的垂直维度，初始化后不可更改（final）。
    private final int rows;

    /**
     * The number of columns in the matrix.
     */
    // 列数。矩阵的水平维度，初始化后不可更改（final）。
    private final int columns;

    /**
     * The data in the matrix, in row major form.
     *
     * To get element (r, c): data[r][c]
     *
     * Because this this is computer science, and not math,
     * the indices for both the row and column start at 0.
     */
    // 矩阵数据。使用行主序（Row-Major）存储，即 data[r][c] 表示第 $r$ 行第 $c$ 列的元素。每个元素是一个字节（8-bit）。
    private final byte [] [] data;

    /**
     * Initialize a matrix of zeros.
     *
     * @param initRows The number of rows in the matrix.
     * @param initColumns The number of columns in the matrix.
     */
    // 创建一个指定行列数的零矩阵。
    public Matrix(int initRows, int initColumns) {
        rows = initRows;
        columns = initColumns;
        data = new byte [rows] [];
        for (int r = 0; r < rows; r++) {
            data[r] = new byte [columns];
        }
    }

    /**
     * Initializes a matrix with the given row-major data.
     */
    // 使用现有的二维字节数组初始化矩阵，会进行深度复制以确保数据安全，并校验每一行的列数是否一致。
    public Matrix(byte [] [] initData) {
        rows = initData.length;
        columns = initData[0].length;
        data = new byte [rows] [];
        for (int r = 0; r < rows; r++) {
            if (initData[r].length != columns) {
                throw new IllegalArgumentException("Not all rows have the same number of columns");
            }
            data[r] = new byte[columns];
            for (int c = 0; c < columns; c++) {
                data[r][c] = initData[r][c];
            }
        }
    }

    /**
     * Returns an identity matrix of the given size.
     */
    // 返回一个 $size \times size$ 的单位矩阵（主对角线全为 1，其余为 0）
    public static Matrix identity(int size) {
        Matrix result = new Matrix(size, size);
        for (int i = 0; i < size; i++) {
            result.set(i, i, (byte) 1);
        }
        return result;
    }

    /**
     * Returns a human-readable string of the matrix contents.
     *
     * Example: [[1, 2], [3, 4]]
     */
    @Override
    public String toString() {
        StringBuilder result = new StringBuilder();
        result.append('[');
        for (int r = 0; r < rows; r++) {
            if (r != 0) {
                result.append(", ");
            }
            result.append('[');
            for (int c = 0; c < columns; c++) {
                if (c != 0) {
                    result.append(", ");
                }
                result.append(data[r][c] & 0xFF);
            }
            result.append(']');
        }
        result.append(']');
        return result.toString();
    }

    /**
     * Returns a human-readable string of the matrix contents.
     *
     * Example:
     *    00 01 02
     *    03 04 05
     *    06 07 08
     *    09 0a 0b
     */
    public String toBigString() {
        StringBuilder result = new StringBuilder();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                int value = get(r, c);
                if (value < 0) {
                    value += 256;
                }
                result.append(String.format("%02x ", value));
            }
            result.append("\n");
        }
        return result.toString();
    }

    /**
     * Returns the number of columns in this matrix.
     */
    public int getColumns() {
        return columns;
    }

    /**
     * Returns the number of rows in this matrix.
     */
    public int getRows() {
        return rows;
    }

    /**
     * Returns the value at row r, column c.
     */
    public byte get(int r, int c) {
        if (r < 0 || rows <= r) {
            throw new IllegalArgumentException("Row index out of range: " + r);
        }
        if (c < 0 || columns <= c) {
            throw new IllegalArgumentException("Column index out of range: " + c);
        }
        return data[r][c];
    }

    /**
     * Sets the value at row r, column c.
     */
    public void set(int r, int c, byte value) {
        if (r < 0 || rows <= r) {
            throw new IllegalArgumentException("Row index out of range: " + r);
        }
        if (c < 0 || columns <= c) {
            throw new IllegalArgumentException("Column index out of range: " + c);
        }
        data[r][c] = value;
    }

    /**
     * Returns true iff this matrix is identical to the other.
     */
    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Matrix)) {
            return false;
        }
        for (int r = 0; r < rows; r++) {
            if (!Arrays.equals(data[r], ((Matrix)other).data[r])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Multiplies this matrix (the one on the left) by another
     * matrix (the one on the right).
     */
    // 定义了一个名为 times 的方法，输入参数是另一个矩阵 right（右矩阵），返回结果是一个新的矩阵。
    public Matrix times(Matrix right) {
        // 这是矩阵乘法的先决条件检查。只有当左矩阵的“列数”等于右矩阵的“行数”时，乘法才有意义。如果不相等，程序会直接报错（抛出非法参数异常）。
        if (getColumns() != right.getRows()) {
            throw new IllegalArgumentException(
                    "Columns on left (" + getColumns() +") " +
                    "is different than rows on right (" + right.getRows() + ")");
        }
        // 初始化结果矩阵。新矩阵的规格是：行数等于左矩阵的行数，列数等于右矩阵的列数。
        Matrix result = new Matrix(getRows(), right.getColumns());
        for (int r = 0; r < getRows(); r++) { // 遍历每一行
            for (int c = 0; c < right.getColumns(); c++) { // 遍历每一列
                // 这两层嵌套循环负责定位结果矩阵中的每一个点 $(r, c)$。我们需要计算结果矩阵中第 $r$ 行、第 $c$ 列的那个数值。
                byte value = 0;
                for (int i = 0; i < getColumns(); i++) {
                    // get(r, i) 和 right.get(i, c)：取出左矩阵的一行和右矩阵的一列中对应的元素。
                    // Galois.multiply(...)：这是关键！它不是普通的 a * b，而是执行我们在上一个回答里提到的多项式乘法并取模。
                    // ^= (异或赋值)：这对应了伽罗华域里的加法。在普通矩阵乘法里我们用 +=，但在 $GF(2^8)$ 中，加法就是异或（XOR）。
                    value ^= Galois.multiply(get(r, i), right.get(i, c));
                }
                result.set(r, c, value);
            }
        }
        return result;
    }

    /**
     * Returns the concatenation of this matrix and the matrix on the right.
     */
    // augment（增广）方法就简单得多——它在数学上对应的是矩阵的水平拼接。
    // 在纠错码（如 Reed-Solomon）中，我们经常需要把一个“单位矩阵”和一个“编码矩阵”横向拼在一起，组成一个增广矩阵，以便后续进行高斯消元求逆矩阵。
    // 作用是将当前的矩阵（左边）和参数中的 right 矩阵（右边）像拼图一样横向拼接起来。
    // 在 Reed-Solomon 纠错中，这个操作通常用于以下场景：
    // [ A | I ]
    // 我们把矩阵 A 和单位矩阵 I 拼接在一起。当我们对这个整体进行高斯消元，把左边的 A 变成单位矩阵时，右边的 I 就会神奇地变成 A 的逆矩阵 ($A^{-1}$)。
    public Matrix augment(Matrix right) {
        // 要横向拼接两个矩阵，它们的高度（行数）必须完全一致。如果一个 3 行，一个 5 行，侧面就对不齐了，所以程序会抛出异常。
        if (rows != right.rows) {
            throw new IllegalArgumentException("Matrices don't have the same number of rows");
        }
        // 新矩阵的高度依然是 rows。
        // 新矩阵的宽度是两个原矩阵宽度之和（columns + right.columns）。
        Matrix result = new Matrix(rows, columns + right.columns);
        // 开始逐行处理。因为拼接是横向的，所以我们一行一行地把数据填进去。
        for (int r = 0; r < rows; r++) {
            // 内层循环把当前矩阵（原始矩阵）的第 r 行数据原封不动地复制到新矩阵的左半部分。
            for (int c = 0; c < columns; c++) {
                result.data[r][c] = data[r][c];
            }
            // 它把 right 矩阵的第 r 行数据，接在新矩阵第 r 行的末尾。
            for (int c = 0; c < right.columns; c++) {
                result.data[r][columns + c] = right.data[r][c];
            }
        }
        return result;
    }

    /**
     * Returns a part of this matrix.
     */
    // 如果说前面的 augment 是“拼图”，那么 submatrix 就是**“裁剪”**。
    // 在矩阵运算中，我们经常需要从一个大矩阵中切出一块小矩阵（子矩阵）。例如在纠错解码时，如果某些数据块丢失了，我们需要从编码矩阵中提取出与剩余数据块对应的部分进行计算。
    // rmin, cmin：起始行和起始列（包含）。
    // rmax, cmax：结束行和结束列（不包含，这是计算机科学中常见的左闭右开习惯）。
    public Matrix submatrix(int rmin, int cmin, int rmax, int cmax) {
        Matrix result = new Matrix(rmax - rmin, cmax - cmin);
        for (int r = rmin; r < rmax; r++) {
            for (int c = cmin; c < cmax; c++) {
                result.data[r - rmin][c - cmin] = data[r][c];
            }
        }
        return result;
    }

    /**
     * Returns one row of the matrix as a byte array.
     */
    public byte [] getRow(int row) {
        byte [] result = new byte [columns];
        for (int c = 0; c < columns; c++) {
            result[c] = get(row, c);
        }
        return result;
    }

    /**
     * Exchanges two rows in the matrix.
     */
    public void swapRows(int r1, int r2) {
        if (r1 < 0 || rows <= r1 || r2 < 0 || rows <= r2) {
            throw new IllegalArgumentException("Row index out of range");
        }
        byte [] tmp = data[r1];
        data[r1] = data[r2];
        data[r2] = tmp;
    }

    /**
     * Returns the inverse of this matrix.
     *
     * @throws IllegalArgumentException when the matrix is singular and
     * doesn't have an inverse.
     */
    // invert（求逆）方法是整个矩阵类中最具“魔力”的时刻
    // 在纠错算法中，求逆矩阵的意义在于：如果“乘法”是把数据编码（加密/打散），那么“求逆”就是寻找那把唯一的钥匙，把乱序的数据还原回初始状态。
    // 采用的是数学中经典的高斯-约当消元法（Gauss-Jordan Elimination）
    // 定义求逆方法。它的目标是找到一个矩阵 $B$，使得 $A \times B = I$（$I$ 是单位矩阵，相当于数字里的 1）。
    public Matrix invert() {
        // Sanity check.
        // 合法性检查。在数学上，只有方阵（行数等于列数的矩阵）才有可能存在逆矩阵。如果不是方阵，直接报错。
        if (rows != columns) {
            throw new IllegalArgumentException("Only square matrices can be inverted");
        }

        // Create a working matrix by augmenting this one with
        // an identity matrix on the right.
        // 构建增广矩阵。这是高斯消元的第一步。
        // 把原始矩阵 $A$ 和一个同等大小的单位矩阵 $I$（对角线全为 1，其余为 0）横向拼在一起。
        // 形象地说，现在工作矩阵 work 的形状是 $[A | I]$。
        Matrix work = augment(identity(rows));

        // Do Gaussian elimination to transform the left half into
        // an identity matrix.
        // 高斯消元。
        // 程序对整个 work 矩阵进行一系列行变换（利用伽罗华域的加减乘除）。
        // 数学原理：如果通过行变换把左半部分的 $A$ 变成了单位矩阵 $I$，那么根据线性代数对称性，右半部分的 $I$ 就会自动变成 $A$ 的逆矩阵 $A^{-1}$。
        // 变换过程：$[A | I] \xrightarrow{\text{消元}} [I | A^{-1}]$。
        work.gaussianElimination();

        // The right half is now the inverse.
        // 消元完成后，work 矩阵的左边一半已经变成了单位矩阵，完成了使命。
        // 把右边那一半（从第 rows 列到第 columns * 2 列）切出来。
        return work.submatrix(0, rows, columns, columns * 2);
    }

    /**
     * Does the work of matrix inversion.
     *
     * Assumes that this is an r by 2r matrix.
     */
    private void gaussianElimination() {
        // Clear out the part below the main diagonal and scale the main
        // diagonal to be 1.
        for (int r = 0; r < rows; r++) {
            // If the element on the diagonal is 0, find a row below
            // that has a non-zero and swap them.
            if (data[r][r] == (byte) 0) {
                for (int rowBelow = r + 1; rowBelow < rows; rowBelow++) {
                    if (data[rowBelow][r] != 0) {
                        swapRows(r, rowBelow);
                        break;
                    }
                }
            }
            // If we couldn't find one, the matrix is singular.
            if (data[r][r] == (byte) 0) {
                throw new IllegalArgumentException("Matrix is singular");
            }
            // Scale to 1.
            if (data[r][r] != (byte) 1) {
                byte scale = Galois.divide((byte) 1, data[r][r]);
                for (int c = 0; c < columns; c++) {
                    data[r][c] = Galois.multiply(data[r][c], scale);
                }
            }
            // Make everything below the 1 be a 0 by subtracting
            // a multiple of it.  (Subtraction and addition are
            // both exclusive or in the Galois field.)
            for (int rowBelow = r + 1; rowBelow < rows; rowBelow++) {
                if (data[rowBelow][r] != (byte) 0) {
                    byte scale = data[rowBelow][r];
                    for (int c = 0; c < columns; c++) {
                        data[rowBelow][c] ^= Galois.multiply(scale, data[r][c]);
                    }
                }
            }
        }

        // Now clear the part above the main diagonal.
        for (int d = 0; d < rows; d++) {
            for (int rowAbove = 0; rowAbove < d; rowAbove++) {
                if (data[rowAbove][d] != (byte) 0) {
                    byte scale = data[rowAbove][d];
                    for (int c = 0; c < columns; c++) {
                        data[rowAbove][c] ^= Galois.multiply(scale, data[d][c]);
                    }

                }
            }
        }
    }

}
