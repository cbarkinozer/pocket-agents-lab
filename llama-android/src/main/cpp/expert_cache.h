#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

// A bounded, file-backed cache for a single expert tensor slice.
// This is deliberately independent of llama.cpp graph code until its access
// contract is stable enough to integrate without changing model semantics.
class ExpertCache {
public:
    struct Stats {
        uint64_t hits = 0;
        uint64_t misses = 0;
        uint64_t evictions = 0;
        uint64_t bytes_read = 0;
        uint64_t resident_bytes = 0;
        uint64_t resident_entries = 0;
    };

    explicit ExpertCache(uint64_t budget_bytes = 0);
    ~ExpertCache();

    ExpertCache(const ExpertCache &) = delete;
    ExpertCache & operator=(const ExpertCache &) = delete;

    bool open(const std::string & path, uint64_t budget_bytes);
    void close();
    void clear();

    // Loads one expert slice into the bounded cache. A hit returns true
    // without reading the file again; a miss reads exactly [offset, length).
    bool load(uint32_t layer, uint32_t expert, uint64_t offset, uint64_t length);

    // Enqueues a best-effort background load. The request may still be pending
    // when this method returns; callers must use load() for a synchronous need.
    bool prefetch(uint32_t layer, uint32_t expert, uint64_t offset, uint64_t length);

    Stats stats() const;
    bool is_open() const;

private:
    struct Key {
        uint32_t layer;
        uint32_t expert;

        bool operator==(const Key & other) const {
            return layer == other.layer && expert == other.expert;
        }
    };

    struct KeyHash {
        size_t operator()(const Key & key) const;
    };

    struct Item {
        Key key;
        uint64_t offset;
        uint64_t length;
        std::shared_ptr<std::vector<uint8_t>> bytes;
    };

    struct Impl;
    std::unique_ptr<Impl> impl_;
};
