// AUTOGENERATED FILE - DO NOT MODIFY!
// This file generated by Djinni from test.djinni

#pragma once

#include <atomic>
#include <experimental/optional>
#include "test_helpers.hpp"
#ifdef __cplusplus
extern "C" {
#endif

#include "cw__test_helpers.h"

#ifdef __cplusplus
}
#endif
struct DjinniWrapperTestHelpers final {
    DjinniWrapperTestHelpers(std::shared_ptr<::testsuite::TestHelpers>wo): wrapped_obj(wo) {};

    static std::shared_ptr<::testsuite::TestHelpers> get(djinni::Handle<DjinniWrapperTestHelpers> dw);
    static djinni::Handle<DjinniWrapperTestHelpers> wrap(std::shared_ptr<::testsuite::TestHelpers> obj);

    const std::shared_ptr<::testsuite::TestHelpers> wrapped_obj;
    std::atomic<size_t> ref_count {1};
};