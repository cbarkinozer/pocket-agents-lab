#include "expert_index.h"

#include "ggml.h"

#include <algorithm>
#include <fcntl.h>
#include <limits>
#include <sys/stat.h>
#include <unistd.h>

namespace {

class Reader {
public:
    explicit Reader(int fd) : fd_(fd) {}

    bool read_bytes(void * out, size_t size) {
        auto * dst = static_cast<uint8_t *>(out);
        size_t done = 0;
        while (done < size) {
            const ssize_t n = ::pread(fd_, dst + done, size - done, static_cast<off_t>(pos_));
            if (n <= 0) {
                return false;
            }
            done += static_cast<size_t>(n);
            pos_ += static_cast<uint64_t>(n);
        }
        return true;
    }

    bool u32(uint32_t & value) { return read_bytes(&value, sizeof(value)); }
    bool u64(uint64_t & value) { return read_bytes(&value, sizeof(value)); }

    bool string(std::string & value) {
        uint64_t length = 0;
        if (!u64(length) || length > 1024 * 1024) {
            return false;
        }
        value.resize(static_cast<size_t>(length));
        return length == 0 || read_bytes(value.data(), value.size());
    }

    uint64_t position() const { return pos_; }

private:
    int fd_;
    uint64_t pos_ = 0;
};

enum ValueType : uint32_t {
    UINT8 = 0, INT8 = 1, UINT16 = 2, INT16 = 3, UINT32 = 4, INT32 = 5,
    FLOAT32 = 6, BOOL = 7, STRING = 8, ARRAY = 9, UINT64 = 10, INT64 = 11,
    FLOAT64 = 12,
};

bool skip_value(Reader & reader, uint32_t type) {
    uint8_t small[8] {};
    switch (type) {
    case UINT8:
    case INT8:
    case BOOL:
        return reader.read_bytes(small, 1);
    case UINT16:
    case INT16:
        return reader.read_bytes(small, 2);
    case UINT32:
    case INT32:
    case FLOAT32:
        return reader.read_bytes(small, 4);
    case UINT64:
    case INT64:
    case FLOAT64:
        return reader.read_bytes(small, 8);
    case STRING: {
        std::string value;
        return reader.string(value);
    }
    case ARRAY: {
        uint32_t element_type = 0;
        uint64_t count = 0;
        if (!reader.u32(element_type) || !reader.u64(count) || count > 100000000) {
            return false;
        }
        for (uint64_t i = 0; i < count; ++i) {
            if (!skip_value(reader, element_type)) {
                return false;
            }
        }
        return true;
    }
    default:
        return false;
    }
}

uint64_t align_up(uint64_t value, uint64_t alignment) {
    if (alignment == 0 || value > std::numeric_limits<uint64_t>::max() - alignment + 1) {
        return 0;
    }
    return (value + alignment - 1) / alignment * alignment;
}

bool parse_expert_name(const std::string & name, uint32_t & layer, std::string & projection) {
    if (name.rfind("blk.", 0) != 0) {
        return false;
    }
    const size_t layer_end = name.find('.', 4);
    if (layer_end == std::string::npos || layer_end == 4) {
        return false;
    }
    try {
        layer = static_cast<uint32_t>(std::stoul(name.substr(4, layer_end - 4)));
    } catch (...) {
        return false;
    }
    const std::string suffix = name.substr(layer_end + 1);
    if (suffix == "ffn_gate_exps.weight") {
        projection = "gate";
    } else if (suffix == "ffn_down_exps.weight") {
        projection = "down";
    } else if (suffix == "ffn_up_exps.weight") {
        projection = "up";
    } else {
        return false;
    }
    return true;
}

} // namespace

bool ExpertIndex::open(const std::string & path) {
    tensors_.clear();
    architecture_.clear();
    expert_count_ = 0;

    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return false;
    }
    uint64_t file_size = 0;
    struct stat st {};
    if (fstat(fd, &st) != 0 || st.st_size < 0) {
        ::close(fd);
        return false;
    }
    file_size = static_cast<uint64_t>(st.st_size);

    Reader reader(fd);
    char magic[4] {};
    uint32_t version = 0;
    uint64_t tensor_count = 0;
    uint64_t kv_count = 0;
    if (!reader.read_bytes(magic, sizeof(magic)) || std::string(magic, 4) != "GGUF"
            || !reader.u32(version) || version < 2 || version > 3
            || !reader.u64(tensor_count) || !reader.u64(kv_count)
            || tensor_count > 1000000 || kv_count > 1000000) {
        ::close(fd);
        return false;
    }

    uint64_t alignment = 32;
    for (uint64_t i = 0; i < kv_count; ++i) {
        std::string key;
        uint32_t type = 0;
        if (!reader.string(key) || !reader.u32(type)) {
            ::close(fd);
            return false;
        }
        if (key == "general.architecture" && type == STRING) {
            if (!reader.string(architecture_)) {
                ::close(fd);
                return false;
            }
        } else if (key == "general.alignment" && type == UINT32) {
            uint32_t value = 0;
            if (!reader.u32(value)) {
                ::close(fd);
                return false;
            }
            alignment = value;
        } else if (key.size() > 13 && key.compare(key.size() - 13, 13, ".expert_count") == 0 && type == UINT32) {
            if (!reader.u32(expert_count_)) {
                ::close(fd);
                return false;
            }
        } else if (!skip_value(reader, type)) {
            ::close(fd);
            return false;
        }
    }

    struct Pending {
        uint32_t layer;
        std::string projection;
        uint64_t offset;
        uint64_t tensor_bytes;
    };
    std::vector<Pending> pending;
    pending.reserve(static_cast<size_t>(tensor_count));
    for (uint64_t i = 0; i < tensor_count; ++i) {
        std::string name;
        uint32_t dimensions = 0;
        if (!reader.string(name) || !reader.u32(dimensions) || dimensions == 0 || dimensions > 4) {
            ::close(fd);
            return false;
        }
        std::vector<uint64_t> shape(dimensions);
        for (auto & dimension : shape) {
            if (!reader.u64(dimension) || dimension == 0) {
                ::close(fd);
                return false;
            }
        }
        uint32_t type_value = 0;
        uint64_t tensor_offset = 0;
        if (!reader.u32(type_value) || !reader.u64(tensor_offset)) {
            ::close(fd);
            return false;
        }
        uint32_t layer = 0;
        std::string projection;
        if (!parse_expert_name(name, layer, projection) || expert_count_ == 0) {
            continue;
        }
        if (type_value >= GGML_TYPE_COUNT) {
            continue;
        }
        const auto type = static_cast<ggml_type>(type_value);
        const size_t row_bytes = ggml_row_size(type, static_cast<int64_t>(shape[0]));
        if (row_bytes == 0) {
            continue;
        }
        uint64_t rows = 1;
        for (size_t d = 1; d < shape.size(); ++d) {
            if (rows > std::numeric_limits<uint64_t>::max() / shape[d]) {
                ::close(fd);
                return false;
            }
            rows *= shape[d];
        }
        if (rows > std::numeric_limits<uint64_t>::max() / row_bytes) {
            ::close(fd);
            return false;
        }
        pending.push_back({ layer, projection, tensor_offset, rows * row_bytes });
    }

    const uint64_t data_base = align_up(reader.position(), alignment);
    if (data_base == 0 || data_base > file_size) {
        ::close(fd);
        return false;
    }
    for (const auto & item : pending) {
        if (item.tensor_bytes % expert_count_ != 0 || item.offset > file_size - data_base) {
            continue;
        }
        const uint64_t absolute = data_base + item.offset;
        const uint64_t expert_bytes = item.tensor_bytes / expert_count_;
        if (absolute > file_size || expert_bytes > file_size - absolute) {
            continue;
        }
        tensors_.push_back({ item.layer, item.projection, absolute, item.tensor_bytes, expert_bytes });
    }
    ::close(fd);
    return !architecture_.empty() && expert_count_ > 0;
}

bool ExpertIndex::empty() const { return tensors_.empty(); }
uint32_t ExpertIndex::expert_count() const { return expert_count_; }
const std::string & ExpertIndex::architecture() const { return architecture_; }
const std::vector<ExpertIndex::Tensor> & ExpertIndex::tensors() const { return tensors_; }

std::string ExpertIndex::summary_json() const {
    std::string json = "{\"architecture\":\"" + architecture_
            + "\",\"expertCount\":" + std::to_string(expert_count_)
            + ",\"tensorCount\":" + std::to_string(tensors_.size()) + ",\"tensors\":[";
    for (size_t i = 0; i < tensors_.size(); ++i) {
        const auto & tensor = tensors_[i];
        if (i != 0) {
            json += ",";
        }
        json += "{\"layer\":" + std::to_string(tensor.layer)
                + ",\"projection\":\"" + tensor.projection
                + "\",\"offset\":" + std::to_string(tensor.offset)
                + ",\"tensorBytes\":" + std::to_string(tensor.tensor_bytes)
                + ",\"expertBytes\":" + std::to_string(tensor.expert_bytes) + "}";
    }
    json += "]}";
    return json;
}
