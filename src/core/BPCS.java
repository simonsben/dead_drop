package core;

import utilities.data_management;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.util.HashMap;

import static utilities.data_management.compute_md5;

public class BPCS extends technique {
    public class info_set {
        public byte[][][][] edge_counts;
        public int[] channel_capacities;

        info_set(byte[][][][] _edge_counts, int[] _channel_capacities) {
            edge_counts = _edge_counts;
            channel_capacities = _channel_capacities;
        }
    }

    public static byte threshold = 15;
    private int block_size = 8, block_capacity = block_size * block_size / 8;
    private static Naive naive_encoder = new Naive();
    public HashMap<image, info_set> image_cache = new HashMap<>();

    public void analyze_image(image img) {
        if (image_cache.containsKey(img)) return;

        Raster raster = img.image.getRaster();
        byte[][][][] image_edge_counts = new byte[img.num_channels][][][];
        int[] channel_capacities = new int[img.num_channels];

        for (int channel=0;channel<img.num_channels;channel++) {
            image_edge_counts[channel] = count_edges(raster, channel);
            channel_capacities[channel] = channel_capacity(image_edge_counts[channel]);
            img.data_capacity += channel_capacities[channel];
        }

        image_cache.put(img, new info_set(image_edge_counts, channel_capacities));
    }

    public int channel_capacity(byte[][][] edge_counts) {
        int channel_capacity = 0;

        for (int plane=0;plane<8;plane++) {
            for (int x_index=0;x_index<edge_counts[0].length;x_index++) {
                for (int y_index=0;y_index<edge_counts[0][0].length;y_index++)
                    channel_capacity += (edge_counts[plane][x_index][y_index] >= threshold)? block_capacity : 0;
            }
        }

        return channel_capacity;
    }

    // Counts the bit-plane edges block-wise for a given channel
    // Edge count dimension order: bit-plane, x block, y block
    // TODO modify to count edges for all channels at once
    public byte[][][] count_edges(Raster image, int channel) {
        int width = image.getWidth(), height = image.getHeight(), num_channels = image.getNumBands();
        int x_block, y_block, x_index, difference;
        byte[][][] edge_counts = new byte[8][width / block_size][height / block_size];  // bit plane, x block, y block
        int[] primary_row = new int[width * num_channels], secondary_row = new int[width * num_channels], swap;

        if (channel >= num_channels)
            throw new IllegalArgumentException("Selected channel must exist.");

        image.getPixels(0, 0, width, 1, secondary_row);     // Get base row
        for (int y=0;y<height-1;y++) {
            image.getPixels(0, y + 1, width, 1, primary_row);   // Get next row

            // Make y row primary and y+1 row secondary
            swap = secondary_row;
            secondary_row = primary_row;
            primary_row = swap;

            y_block = y / block_size;                   // Get y block index
            for (int x=0;x<width-1;x++) {
                x_block = x / block_size;               // Get x block index
                x_index = x * num_channels + channel;   // Get x index

                if ((x + 1) % block_size != 0) {        // If x is not on the edge of a block
                    // X primary
                    difference = primary_row[x_index] ^ primary_row[x_index + num_channels];
                    data_management.offload_differences(edge_counts, x_block, y_block, difference);

                    // X secondary
                    difference = secondary_row[x_index] ^ secondary_row[x_index + num_channels];
                    data_management.offload_differences(edge_counts, x_block, (y + 1) / block_size, difference);
                }

                if ((y + 1) % block_size != 0) {    // If y is not on the edge of a block
                    // Y primary
                    difference = primary_row[x_index] ^ secondary_row[x_index];
                    data_management.offload_differences(edge_counts, x_block, y_block, difference);

                    difference = primary_row[x_index + num_channels] ^ secondary_row[x_index + num_channels];
                    data_management.offload_differences(edge_counts, (x + 1) / block_size, y_block, difference);
                }
            }
        }
        if (channel == 0) edge_counts[0][0][0] = 0x7F;  // Mark base block of base channel for header

        return edge_counts;
    }

    public int embed_data(image img, byte[] data, int offset) {
        if (offset > 1) data = encrypt_manager.encrypt_data(data);  // Don't encrypt if its the header
        if (data.length + offset > img.data_capacity)
            throw new IllegalArgumentException(data.length + "B exceeds image capacity of " + img.data_capacity + "B");
        info_set image_info = image_cache.get(img);                 // Get image info from cache

        image sub_image = new image();
        int byte_offset = 0;
        byte[] data_subset = new byte[block_capacity];
        byte[][][][] edge_counts = image_info.edge_counts;
        boolean first = true;

        for (int channel=0;channel<img.num_channels;channel++) {                       // For each channel
            for (int x_index=0;x_index<edge_counts[0][0].length;x_index++){            // For each block on x axis
                for (int y_index=0;y_index<edge_counts[0][0][0].length;y_index++) {    // For each block on y axis
                    sub_image.image = get_sub_image(img, x_index, y_index);

                    for (int plane=0;plane<8;plane++) {
                        if (edge_counts[channel][plane][x_index][y_index] < threshold)  // Skip block if not high-freq
                            continue;
                        if (first && (offset -= block_capacity) >= 0)                    // Skip blocks until
                            continue;
                        else if (first) {                                               // Check for offset on first
                            first = false;
                            offset += block_capacity;
                            byte_offset = insert_data(sub_image, data, data_subset, offset, byte_offset, plane, channel);
                        } else
                            byte_offset = insert_data(sub_image, data, data_subset, 0, byte_offset, plane, channel);

                        if (byte_offset >= data.length) return data.length;
                    }
                }
            }
        }

        return data.length;
    }

    BufferedImage get_sub_image(image img, int x_index, int y_index) {
        return img.image.getSubimage(x_index * block_size, y_index * block_size, block_size, block_size);
    }

    int insert_data(image sub_image, byte[] data, byte[] data_subset, int image_offset, int byte_offset, int bit_plane, int channel) {
        int data_size = Math.min(block_capacity - image_offset, data.length - byte_offset);     // Get block data size
        if (data_size != data_subset.length) data_subset = new byte[data_size];                 // [Redefine] array

        System.arraycopy(data, byte_offset, data_subset, 0, data_size);                 // Copy data
        byte_offset += data_size;                                                               // Increment data offset

        naive_encoder.embed_data(sub_image, data_subset, image_offset, bit_plane, channel);              // Encode data
        return byte_offset;
    }

    public byte[] recover_data(image img, int data_size, int offset) {
        if (!image_cache.containsKey(img)) analyze_image(img);
        info_set image_info = image_cache.get(img);

        image sub_image = new image();
        int byte_offset = 0;
        byte[] data_subset = new byte[block_capacity], data = new byte[data_size];
        byte[][][][] edge_counts = image_info.edge_counts;
        boolean first = true;

        int x_blocks = edge_counts[0][0].length, y_blocks = edge_counts[0][0][0].length;

        for (int channel=0;channel<img.num_channels;channel++) {                    // For each channel
            for (int x_index=0;x_index<x_blocks;x_index++){            // For each block on x axis
                for (int y_index=0;y_index<y_blocks;y_index++) {    // For each block on y axis
                    sub_image.image = get_sub_image(img, x_index, y_index);

                    for (int plane=0;plane<8;plane++) {
                        if (edge_counts[channel][plane][x_index][y_index] < threshold)
                            continue;
                        if (first && (offset -= block_capacity) >= 0)    // Skip blocks until
                            continue;
                        else if (first) {
                            first = false;
                            offset += block_capacity;
                            byte_offset = extract_data(sub_image, data, data_subset, offset, byte_offset, plane, channel);
                        } else
                            byte_offset = extract_data(sub_image, data, data_subset, 0, byte_offset, plane, channel);

                        if (byte_offset >= data.length) {
                            if (!(offset <= 1 && data.length < block_capacity))
                                return encrypt_manager.decrypt_data(data);
                            return data;
                        }
                    }
                }
            }
        }

        if (!(offset == 0 && data.length < block_capacity))
            data = encrypt_manager.decrypt_data(data);

        return data;
    }

    int extract_data(image sub_image, byte[] data, byte[] data_subset, int image_offset, int byte_offset, int bit_plane, int channel) {
        int data_size = Math.min(block_capacity - image_offset, data.length - byte_offset);     // Get block data size
        if (data_size != data_subset.length) data_subset = new byte[data_size];                 // [Redefine] array

        naive_encoder.recover_data(sub_image, data_subset, image_offset, bit_plane, channel);

        System.arraycopy(data_subset, 0, data, byte_offset, data_size);                 // Copy data
        byte_offset += data_size;                                                              // Increment data offset

        return byte_offset;
    }

    public static void visualize_edges(WritableRaster image, byte[][][] edge_counts, int block_size, int bit) {
        int width = image.getWidth(), height = image.getHeight(), num_channels = image.getNumBands();
        int[] row = new int[width * num_channels];
        int x_index, y_index;

        for (int y=0;y<height;y++) {
            image.getPixels(0, y, width, 1, row);
            y_index = y / block_size;

            for (int x=0;x<width;x++) {
                x_index = x / block_size;

                if (edge_counts[bit][x_index][y_index] >= threshold) {
                    row[x * 3] = 255;
                }
            }
            image.setPixels(0, y, width, 1, row);
        }
    }

    public static void add_square_mask(WritableRaster image, int block_size, int channel) {
        int width = image.getWidth(), height = image.getHeight(), num_channels = image.getNumBands();
        int[] row = new int[width * num_channels];

        int x_index, y_index;

        for (int y=0;y<height;y++) {
            image.getPixels(0, y, width, 1, row);
            y_index = y / block_size;

            for (int x=0;x<width;x++) {
                x_index = x / block_size;

                if ((x_index % 2 == 0) ^ (y_index % 2 == 0)) {
                    row[x * 3 + channel] = 255;
                }
            }
            image.setPixels(0, y, width, 1, row);
        }
    }

    public static void add_square_mask(WritableRaster image, int channel) {
        add_square_mask(image, 8, channel);
    }
}
