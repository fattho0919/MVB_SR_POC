#pragma once

#include <string>
#include <memory>
#include <vector>

class SREngine {
public:
    struct Config {
        std::string model_path;
        int num_threads = 4;
        bool use_gpu = false;
        bool use_npu = false;
        int input_width = 0;
        int input_height = 0;
        int output_width = 0;
        int output_height = 0;
    };
    
    SREngine();
    ~SREngine();
    
    bool Initialize(const Config& config);
    bool Process(const uint8_t* input, uint8_t* output, 
                int width, int height);
    void Release();
    
    // Get engine statistics
    struct Stats {
        long long total_processed = 0;
        long long total_time_ms = 0;
        float avg_time_ms = 0.0f;
        size_t memory_usage = 0;
    };
    
    Stats GetStats() const;
    
    // Disable copy
    SREngine(const SREngine&) = delete;
    SREngine& operator=(const SREngine&) = delete;
    
private:
    class Impl;
    std::unique_ptr<Impl> pImpl;
};