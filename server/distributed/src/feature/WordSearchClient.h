#include "WordSearchService.grpc.pb.h"
#include <grpc++/grpc++.h>
#include <iostream>
#include <memory>
#include <opencv2/core/types.hpp>
#include <string>

class CameraModel;
class Session;

class WordSearchClient {
public:
  explicit WordSearchClient(std::shared_ptr<grpc::Channel> channel);

  // Assembles the client's payload, sends it and presents the response back
  // from the server.
  bool onFeature(const std::vector<cv::KeyPoint> &keyPoints,
                 const cv::Mat &descriptors, const CameraModel &camera,
                 const Session &session);

private:
  // Out of the passed in Channel comes the stub, stored here, our view of the
  // server's exposed services.
  std::unique_ptr<proto::WordSearchService::Stub> stub_;
};
