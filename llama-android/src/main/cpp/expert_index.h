#pragma once

#include <cstdint>
#include <string>
#include <vector>

class ExpertIndex {
public:
    struct Tensor {
        uint32_t layer = 0;
        std::string projection;
        uint64_t offset = 0;
        uint64_t tensor_bytes = 0;
        uint64_t expert_bytes = 0;
    };

    bool open(const std::string & path);
    bool empty() const;
    uint32_t expert_count() const;
    const std::string & architecture() const;
    const std::vector<Tensor> & tensors() const;
    std::string summary_json() const;

private:
    uint32_t expert_count_ = 0;
    std::string architecture_;
    std::vector<Tensor> tensors_;
};
