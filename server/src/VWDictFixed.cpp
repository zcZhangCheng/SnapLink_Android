#include <rtabmap/core/Signature.h>
#include <rtabmap/core/DBDriver.h>
#include <rtabmap/core/Parameters.h>
#include <rtabmap/utilite/UtiLite.h>
#include <opencv2/opencv_modules.hpp>
#include <flann/flann.hpp>
#include <fstream>
#include <string>
#include "VWDictFixed.h"

class FlannIndex
{
public:
    FlannIndex():
        index_(0),
        nextIndex_(0),
        featuresDim_(0)
    {
    }

    virtual ~FlannIndex()
    {
        this->release();
    }

    void release()
    {
        if (index_)
        {
            delete(flann::Index<flann::L2<float> > *)index_;
            index_ = 0;
        }
        nextIndex_ = 0;
        addedDescriptors_.clear();
    }

    void build(
        const cv::Mat &features,
        const flann::IndexParams &params)
    {
        this->release();
        UASSERT(index_ == 0);
        UASSERT(features.type() == CV_32FC1 || features.type() == CV_8UC1);
        featuresDim_ = features.cols;

        index_ = new flann::Index<flann::L2<float> >(dataset, params);
        ((flann::Index<flann::L2<float> > *)index_)->buildIndex();

        if (features.rows == 1)
        {
            // incremental FLANN
            addedDescriptors_.insert(std::make_pair(nextIndex_, features));
        }
        // else assume that the features are kept in memory outside this class (e.g., dataTree_)

        nextIndex_ = features.rows;
    }

    bool isBuilt()
    {
        return index_ != 0;
    }

    int featuresDim() const
    {
        return featuresDim_;
    }

    void knnSearch(
        const cv::Mat &query,
        cv::Mat &indices,
        cv::Mat &dists,
        int knn,
        const flann::SearchParams &params = flann::SearchParams())
    {
        if (!index_)
        {
            UERROR("Flann index not yet created!");
            return;
        }
        indices.create(query.rows, knn, CV_32S);
        dists.create(query.rows, knn, CV_32F);

        flann::Matrix<int> indicesF((int *)indices.data, indices.rows, indices.cols);

        flann::Matrix<float> distsF((float *)dists.data, dists.rows, dists.cols);
        flann::Matrix<float> queryF((float *)query.data, query.rows, query.cols);
        ((flann::Index<flann::L2<float> > *)index_)->knnSearch(queryF, indicesF, distsF, knn, params);
    }

private:
    void *index_;
    unsigned int nextIndex_;
    int featuresDim_;

    // keep feature in memory until the tree is rebuilt
    // (in case the word is deleted when removed from the VWDictFixed)
    std::map<int, cv::Mat> addedDescriptors_;
};

const int VWDictFixed::ID_START = 1;
const int VWDictFixed::ID_INVALID = 0;

VWDictFixed::VWDictFixed(const rtabmap::ParametersMap &parameters) :
    _totalActiveReferences(0),
    _flannIndex(new FlannIndex()),
    _strategy(kNNFlannKdTree)
{
}

VWDictFixed::~VWDictFixed()
{
    this->clear();
    delete _flannIndex;
}

void VWDictFixed::update()
{
    ULOGGER_DEBUG("");
    if (!_notIndexedWords.size())
    {
        // No need to update the search index if we
        // use a fixed dictionary and the index is
        // already built
        return;
    }

    if (_notIndexedWords.size() || _visualWords.size() == 0 || _removedIndexedWords.size())
    {
        _mapIndexId.clear();
        _mapIdIndex.clear();
        _dataTree = cv::Mat();
        _flannIndex->release();

        if (_visualWords.size())
        {
            UTimer timer;
            timer.start();

            int type;
            if (_visualWords.begin()->second->getDescriptor().type() == CV_8U)
            {
                if (_strategy == kNNFlannKdTree)
                {
                    type = CV_32F;
                }
                else
                {
                    UFATAL("");
                }
            }
            else
            {
                type = _visualWords.begin()->second->getDescriptor().type();
            }
            int dim = _visualWords.begin()->second->getDescriptor().cols;

            UASSERT(type == CV_32F || type == CV_8U);
            UASSERT(dim > 0);

            // Create the data matrix
            _dataTree = cv::Mat(_visualWords.size(), dim, type); // SURF descriptors are CV_32F
            std::map<int, rtabmap::VisualWord *>::const_iterator iter = _visualWords.begin();
            for (unsigned int i = 0; i < _visualWords.size(); ++i, ++iter)
            {
                cv::Mat descriptor;
                if (iter->second->getDescriptor().type() == CV_8U)
                {
                    if (_strategy == kNNFlannKdTree)
                    {
                        iter->second->getDescriptor().convertTo(descriptor, CV_32F);
                    }
                    else
                    {
                        UFATAL("");
                    }
                }
                else
                {
                    descriptor = iter->second->getDescriptor();
                }

                UASSERT(descriptor.cols == dim);
                UASSERT(descriptor.type() == type);

                descriptor.copyTo(_dataTree.row(i));
                _mapIndexId.insert(_mapIndexId.end(), std::pair<int, int>(i, iter->second->id()));
                _mapIdIndex.insert(_mapIdIndex.end(), std::pair<int, int>(iter->second->id(), i));
            }

            ULOGGER_DEBUG("_mapIndexId.size() = %d, words.size()=%d, _dim=%d", _mapIndexId.size(), _visualWords.size(), dim);
            ULOGGER_DEBUG("copying data = %f s", timer.ticks());

            if (_strategy ==  kNNFlannKdTree)
            {
                UASSERT_MSG(type == CV_32F, "To use KdTree dictionary, float descriptors are required!");
                _flannIndex->build(_dataTree, flann::KDTreeIndexParams());
            }
            else
            {
                UFATAL("");
            }

            ULOGGER_DEBUG("Time to create kd tree = %f s", timer.ticks());
        }
        UDEBUG("Dictionary updated! (size=%d added=%d removed=%d)",
               _dataTree.rows, _notIndexedWords.size(), _removedIndexedWords.size());
    }
    else
    {
        UDEBUG("Dictionary has not changed, so no need to update it! (size=%d)", _dataTree.rows);
    }
    _notIndexedWords.clear();
    _removedIndexedWords.clear();
    UDEBUG("");
}

void VWDictFixed::clear(bool printWarningsIfNotEmpty)
{
    ULOGGER_DEBUG("");
    if (printWarningsIfNotEmpty)
    {
        if (_visualWords.size())
        {
            UWARN("Visual dictionary would be already empty here (%d words still in dictionary).", (int)_visualWords.size());
        }
        if (_notIndexedWords.size())
        {
            UWARN("Not indexed words should be empty here (%d words still not indexed)", (int)_notIndexedWords.size());
        }
    }
    for (std::map<int, rtabmap::VisualWord *>::iterator i = _visualWords.begin(); i != _visualWords.end(); ++i)
    {
        delete(*i).second;
    }
    _visualWords.clear();
    _notIndexedWords.clear();
    _removedIndexedWords.clear();
    _totalActiveReferences = 0;
    _dataTree = cv::Mat();
    _mapIndexId.clear();
    _mapIdIndex.clear();
    _unusedWords.clear();
    _flannIndex->release();
}

void VWDictFixed::addWordRef(int wordId, int signatureId)
{
    if (signatureId > 0 && wordId > 0)
    {
        rtabmap::VisualWord *vw = 0;
        vw = uValue(_visualWords, wordId, vw);
        if (vw)
        {
            vw->addRef(signatureId);
            _totalActiveReferences += 1;

            _unusedWords.erase(vw->id());
        }
        else
        {
            UERROR("Not found word %d", wordId);
        }
    }
}

void VWDictFixed::removeAllWordRef(int wordId, int signatureId)
{
    rtabmap::VisualWord *vw = 0;
    vw = uValue(_visualWords, wordId, vw);
    if (vw)
    {
        _totalActiveReferences -= vw->removeAllRef(signatureId);
        if (vw->getReferences().size() == 0)
        {
            _unusedWords.insert(std::pair<int, rtabmap::VisualWord *>(vw->id(), vw));
        }
    }
}

std::vector<int> VWDictFixed::findNN(const std::list<rtabmap::VisualWord *> &vws) const
{
    UTimer timer;
    timer.start();

    if (_visualWords.size() && vws.size())
    {
        int type = (*vws.begin())->getDescriptor().type();
        int dim = (*vws.begin())->getDescriptor().cols;

        if (dim != _visualWords.begin()->second->getDescriptor().cols)
        {
            UERROR("Descriptors (size=%d) are not the same size as already added words in dictionary(size=%d)", (*vws.begin())->getDescriptor().cols, dim);
            return std::vector<int>(vws.size(), 0);
        }

        if (type != _visualWords.begin()->second->getDescriptor().type())
        {
            UERROR("Descriptors (type=%d) are not the same type as already added words in dictionary(type=%d)", (*vws.begin())->getDescriptor().type(), type);
            return std::vector<int>(vws.size(), 0);
        }

        // fill the request matrix
        int index = 0;
        rtabmap::VisualWord *vw;
        cv::Mat query(vws.size(), dim, type);
        for (std::list<rtabmap::VisualWord *>::const_iterator iter = vws.begin(); iter != vws.end(); ++iter, ++index)
        {
            vw = *iter;
            UASSERT(vw);

            UASSERT(vw->getDescriptor().cols == dim);
            UASSERT(vw->getDescriptor().type() == type);

            vw->getDescriptor().copyTo(query.row(index));
        }
        ULOGGER_DEBUG("Preparation time = %fs", timer.ticks());

        return findNN(query);
    }
    return std::vector<int>(vws.size(), 0);
}

std::vector<int> VWDictFixed::findNN(const cv::Mat &queryIn) const
{
    UTimer timer;
    timer.start();
    std::vector<int> resultIds(queryIn.rows, 0);
    unsigned int k = 2; // k nearest neighbor

    if (_visualWords.size() && queryIn.rows)
    {
        // verify we have the same features
        int dim = _visualWords.begin()->second->getDescriptor().cols;
        int type = _visualWords.begin()->second->getDescriptor().type();
        UASSERT(type == CV_32F || type == CV_8U);

        if (dim != queryIn.cols)
        {
            UERROR("Descriptors (size=%d) are not the same size as already added words in dictionary(size=%d)", queryIn.cols, dim);
            return resultIds;
        }
        if (type != queryIn.type())
        {
            UERROR("Descriptors (type=%d) are not the same type as already added words in dictionary(type=%d)", queryIn.type(), type);
            return resultIds;
        }

        // now compare with the actual index
        cv::Mat query;
        if (queryIn.type() == CV_8U)
        {
            if (_strategy == kNNFlannKdTree)
            {
                queryIn.convertTo(query, CV_32F);
            }
            else
            {
                UFATAL("");
            }
        }
        else
        {
            query = queryIn;
        }
        dim = 0;
        type = -1;
        if (_dataTree.rows || _flannIndex->isBuilt())
        {
            dim = _flannIndex->isBuilt() ? _flannIndex->featuresDim() : _dataTree.cols;
            type = _flannIndex->isBuilt() ? _flannIndex->featuresType() : _dataTree.type();
            UASSERT(type == CV_32F || type == CV_8U);
        }

        if (dim && dim != query.cols)
        {
            UERROR("Descriptors (size=%d) are not the same size as already added words in dictionary(size=%d)", query.cols, dim);
            return resultIds;
        }

        if (type >= 0 && type != query.type())
        {
            UERROR("Descriptors (type=%d) are not the same type as already added words in dictionary(type=%d)", query.type(), type);
            return resultIds;
        }

        std::vector<std::vector<cv::DMatch> > matches;
        bool bruteForce = false;
        cv::Mat results;
        cv::Mat dists;

        if (_flannIndex->isBuilt() || (!_dataTree.empty() && _dataTree.rows >= (int)k))
        {
            //Find nearest neighbors
            UDEBUG("query.rows=%d ", query.rows);

            if (_strategy == kNNFlannKdTree)
            {
                _flannIndex->knnSearch(query, results, dists, k);
            }
            else
            {
                UFATAL("");
            }

            // In case of binary descriptors
            if (dists.type() == CV_32S)
            {
                cv::Mat temp;
                dists.convertTo(temp, CV_32F);
                dists = temp;
            }
        }
        ULOGGER_DEBUG("Search dictionary time = %fs", timer.ticks());

        std::map<int, int> mapIndexIdNotIndexed;
        std::vector<std::vector<cv::DMatch> > matchesNotIndexed;
        if (_notIndexedWords.size())
        {
            cv::Mat dataNotIndexed = cv::Mat::zeros(_notIndexedWords.size(), query.cols, query.type());
            unsigned int index = 0;
            rtabmap::VisualWord *vw;
            for (std::set<int>::iterator iter = _notIndexedWords.begin(); iter != _notIndexedWords.end(); ++iter, ++index)
            {
                vw = _visualWords.at(*iter);

                cv::Mat descriptor;
                if (vw->getDescriptor().type() == CV_8U)
                {
                    if (_strategy == kNNFlannKdTree)
                    {
                        vw->getDescriptor().convertTo(descriptor, CV_32F);
                    }
                    else
                    {
                        UFATAL("");
                    }
                }
                else
                {
                    descriptor = vw->getDescriptor();
                }

                UASSERT(vw != 0 && descriptor.cols == query.cols && descriptor.type() == query.type());
                vw->getDescriptor().copyTo(dataNotIndexed.row(index));
                mapIndexIdNotIndexed.insert(mapIndexIdNotIndexed.end(), std::pair<int, int>(index, vw->id()));
            }

            // Find nearest neighbor
            ULOGGER_DEBUG("Searching in words not indexed...");
            cv::BFMatcher matcher(query.type() == CV_8U ? cv::NORM_HAMMING : cv::NORM_L2SQR);
            matcher.knnMatch(query, dataNotIndexed, matchesNotIndexed, dataNotIndexed.rows > 1 ? 2 : 1);
        }
        ULOGGER_DEBUG("Search not yet indexed words time = %fs", timer.ticks());

        for (int i = 0; i < query.rows; ++i)
        {
            std::multimap<float, int> fullResults; // Contains results from the kd-tree search [and the naive search in new words]
            if (!bruteForce && dists.cols)
            {
                for (int j = 0; j < dists.cols; ++j)
                {
                    float d = dists.at<float>(i, j);
                    int id = uValue(_mapIndexId, results.at<int>(i, j));
                    if (d >= 0.0f && id > 0)
                    {
                        fullResults.insert(std::pair<float, int>(d, id));
                    }
                }
            }
            else if (bruteForce && matches.size())
            {
                for (unsigned int j = 0; j < matches.at(i).size(); ++j)
                {
                    float d = matches.at(i).at(j).distance;
                    int id = uValue(_mapIndexId, matches.at(i).at(j).trainIdx);
                    if (d >= 0.0f && id > 0)
                    {
                        fullResults.insert(std::pair<float, int>(d, id));
                    }
                }
            }

            // not indexed..
            if (matchesNotIndexed.size())
            {
                for (unsigned int j = 0; j < matchesNotIndexed.at(i).size(); ++j)
                {
                    float d = matchesNotIndexed.at(i).at(j).distance;
                    int id = uValue(mapIndexIdNotIndexed, matchesNotIndexed.at(i).at(j).trainIdx);
                    if (d >= 0.0f && id > 0)
                    {
                        fullResults.insert(std::pair<float, int>(d, id));
                    }
                    else
                    {
                        break;
                    }
                }
            }

            if (fullResults.size())
            {
                //Just take the nearest if the dictionary is not incremental
                resultIds[i] = fullResults.begin()->second; // Accepted
            }
        }
        ULOGGER_DEBUG("badDist check time = %fs", timer.ticks());
    }
    return resultIds;
}

void VWDictFixed::addWord(rtabmap::VisualWord *vw)
{
    if (vw)
    {
        _visualWords.insert(std::pair<int, rtabmap::VisualWord *>(vw->id(), vw));
        _notIndexedWords.insert(vw->id());
        if (vw->getReferences().size())
        {
            _totalActiveReferences += uSum(uValues(vw->getReferences()));
        }
        else
        {
            _unusedWords.insert(std::pair<int, rtabmap::VisualWord *>(vw->id(), vw));
        }
    }
}

const rtabmap::VisualWord *VWDictFixed::getWord(int id) const
{
    return uValue(_visualWords, id, (rtabmap::VisualWord *)0);
}

rtabmap::VisualWord *VWDictFixed::getUnusedWord(int id) const
{
    return uValue(_unusedWords, id, (rtabmap::VisualWord *)0);
}

std::vector<rtabmap::VisualWord *> VWDictFixed::getUnusedWords() const
{
    // if (!_incrementalDictionary)
    // {
    //     ULOGGER_WARN("This method does nothing on a fixed dictionary");
    //     return std::vector<rtabmap::VisualWord *>();
    // }
    return uValues(_unusedWords);
}

void VWDictFixed::removeWords(const std::vector<rtabmap::VisualWord *> &words)
{
    for (unsigned int i = 0; i < words.size(); ++i)
    {
        _visualWords.erase(words[i]->id());
        _unusedWords.erase(words[i]->id());
        if (_notIndexedWords.erase(words[i]->id()) == 0)
        {
            _removedIndexedWords.insert(words[i]->id());
        }
    }
}

void VWDictFixed::deleteUnusedWords()
{
    std::vector<rtabmap::VisualWord *> unusedWords = uValues(_unusedWords);
    removeWords(unusedWords);
    for (unsigned int i = 0; i < unusedWords.size(); ++i)
    {
        delete unusedWords[i];
    }
}
