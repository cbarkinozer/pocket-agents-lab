#include "expert_cache.h"

#include <algorithm>
#include <cerrno>
#include <condition_variable>
#include <fcntl.h>
#include <deque>
#include <list>
#include <mutex>
#include <sys/stat.h>
#include <thread>
#include <unistd.h>
#include <unordered_map>

struct ExpertCache::Impl {
    int fd = -1;
    uint64_t file_size = 0;
    uint64_t budget_bytes = 0;
    uint64_t resident_bytes = 0;
    Stats stats;
    std::string path;
    std::list<Item> lru;
    std::unordered_map<Key, std::list<Item>::iterator, KeyHash> index;
    struct Request {
        uint32_t layer;
        uint32_t expert;
        uint64_t offset;
        uint64_t length;
    };
    std::deque<Request> requests;
    std::condition_variable request_cv;
    std::thread worker;
    bool stopping = false;
    mutable std::mutex mutex;
};

ExpertCache::ExpertCache(uint64_t budget_bytes) : impl_(std::make_unique<Impl>()) {
    impl_->budget_bytes = budget_bytes;
}

ExpertCache::~ExpertCache() {
    close();
}

size_t ExpertCache::KeyHash::operator()(const Key & key) const {
    return (static_cast<size_t>(key.layer) << 32) ^ static_cast<size_t>(key.expert);
}

bool ExpertCache::open(const std::string & path, uint64_t budget_bytes) {
    close();
    std::lock_guard<std::mutex> lock(impl_->mutex);
    impl_->stats = {};
    impl_->path = path;
    impl_->budget_bytes = budget_bytes;

    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        impl_->path.clear();
        return false;
    }
    struct stat st {};
    if (fstat(fd, &st) != 0 || st.st_size < 0) {
        ::close(fd);
        impl_->path.clear();
        return false;
    }
    impl_->file_size = static_cast<uint64_t>(st.st_size);
    impl_->fd = fd;
    impl_->stopping = false;
    impl_->worker = std::thread([this]() {
        while (true) {
            Impl::Request request {};
            {
                std::unique_lock<std::mutex> lock(impl_->mutex);
                impl_->request_cv.wait(lock, [this]() {
                    return impl_->stopping || !impl_->requests.empty();
                });
                if (impl_->stopping && impl_->requests.empty()) {
                    return;
                }
                request = impl_->requests.front();
                impl_->requests.pop_front();
            }
            load(request.layer, request.expert, request.offset, request.length);
        }
    });
    return true;
}

void ExpertCache::close() {
    {
        std::lock_guard<std::mutex> lock(impl_->mutex);
        impl_->stopping = true;
        impl_->requests.clear();
    }
    impl_->request_cv.notify_all();
    if (impl_->worker.joinable()) {
        impl_->worker.join();
    }
    std::lock_guard<std::mutex> lock(impl_->mutex);
    if (impl_->fd >= 0) {
        ::close(impl_->fd);
        impl_->fd = -1;
    }
    impl_->lru.clear();
    impl_->index.clear();
    impl_->resident_bytes = 0;
    impl_->path.clear();
    impl_->file_size = 0;
}

void ExpertCache::clear() {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    impl_->requests.clear();
    impl_->lru.clear();
    impl_->index.clear();
    impl_->resident_bytes = 0;
    impl_->stats.resident_bytes = 0;
    impl_->stats.resident_entries = 0;
}

bool ExpertCache::prefetch(uint32_t layer, uint32_t expert, uint64_t offset, uint64_t length) {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    if (impl_->fd < 0 || length == 0 || offset > impl_->file_size || length > impl_->file_size - offset) {
        return false;
    }
    if (impl_->requests.size() >= 64) {
        return false;
    }
    impl_->requests.push_back(Impl::Request { layer, expert, offset, length });
    impl_->request_cv.notify_one();
    return true;
}

bool ExpertCache::load(uint32_t layer, uint32_t expert, uint64_t offset, uint64_t length) {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    if (impl_->fd < 0 || length == 0 || offset > impl_->file_size || length > impl_->file_size - offset) {
        return false;
    }

    const Key key { layer, expert };
    const auto found = impl_->index.find(key);
    if (found != impl_->index.end()) {
        auto item = found->second;
        if (item->offset == offset && item->length == length) {
            impl_->lru.splice(impl_->lru.begin(), impl_->lru, item);
            impl_->stats.hits++;
            return true;
        }
        impl_->resident_bytes -= item->length;
        impl_->lru.erase(item);
        impl_->index.erase(found);
    }

    if (impl_->budget_bytes == 0 || length > impl_->budget_bytes) {
        impl_->stats.misses++;
        return false;
    }

    while (impl_->resident_bytes + length > impl_->budget_bytes && !impl_->lru.empty()) {
        auto last = std::prev(impl_->lru.end());
        impl_->resident_bytes -= last->length;
        impl_->index.erase(last->key);
        impl_->lru.pop_back();
        impl_->stats.evictions++;
    }

    auto bytes = std::make_shared<std::vector<uint8_t>>(static_cast<size_t>(length));
    uint64_t read_total = 0;
    while (read_total < length) {
        const ssize_t read_count = ::pread(
                impl_->fd,
                bytes->data() + read_total,
                static_cast<size_t>(std::min<uint64_t>(length - read_total, 1024 * 1024)),
                static_cast<off_t>(offset + read_total));
        if (read_count <= 0) {
            impl_->stats.misses++;
            return false;
        }
        read_total += static_cast<uint64_t>(read_count);
    }

    impl_->lru.push_front(Item { key, offset, length, std::move(bytes) });
    impl_->index[key] = impl_->lru.begin();
    impl_->resident_bytes += length;
    impl_->stats.misses++;
    impl_->stats.bytes_read += length;
    impl_->stats.resident_bytes = impl_->resident_bytes;
    impl_->stats.resident_entries = impl_->lru.size();
    return true;
}

ExpertCache::Stats ExpertCache::stats() const {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    Stats result = impl_->stats;
    result.resident_bytes = impl_->resident_bytes;
    result.resident_entries = impl_->lru.size();
    return result;
}

bool ExpertCache::is_open() const {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    return impl_->fd >= 0;
}
