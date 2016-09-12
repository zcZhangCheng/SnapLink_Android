CONFIG += qt
QT -= gui

CONFIG += c++11

INCLUDEPATH += /usr/local/include/opencv2
LIBS += -L/usr/lib/x86_64-linux-gnu
LIBS += -lboost_system

INCLUDEPATH += /usr/include/eigen3

INCLUDEPATH += /usr/local/include/opencv2
LIBS += -L/usr/local/lib
LIBS += -lopencv_core
LIBS += -lopencv_imgproc
LIBS += -lopencv_features2d
LIBS += -lopencv_imgcodecs
LIBS += -lopencv_calib3d
LIBS += -lopencv_flann
LIBS += -lopencv_xfeatures2d

INCLUDEPATH += /usr/local/include/pcl-1.8
LIBS += -L/usr/local/lib
LIBS += -lpcl_common

INCLUDEPATH += /usr/local/include/rtabmap-0.11
LIBS += -L/usr/local/lib
LIBS += -lrtabmap_core
LIBS += -lrtabmap_utilite

INCLUDEPATH += /usr/include/
LIBS += -L/usr/lib/x86_64-linux-gnu
LIBS += -lmicrohttpd

INCLUDEPATH += ../lib
INCLUDEPATH += src

SOURCES += ../lib/util/Time.cpp
SOURCES += ../lib/util/Utility.cpp
SOURCES += ../lib/adapter/RTABMapDBAdapter.cpp
SOURCES += ../lib/data/SignaturesSimple.cpp
SOURCES += ../lib/data/Transform.cpp
SOURCES += ../lib/data/LabelsSimple.cpp
SOURCES += ../lib/data/Label.cpp
SOURCES += ../lib/data/Signature.cpp
SOURCES += ../lib/data/Word.cpp
SOURCES += ../lib/data/CameraModel.cpp
SOURCES += ../lib/data/WordsKdTree.cpp
SOURCES += ../lib/algo/SignatureSearch.cpp
SOURCES += ../lib/algo/WordSearch.cpp
SOURCES += ../lib/algo/Visibility.cpp
SOURCES += ../lib/algo/Feature.cpp
SOURCES += ../lib/algo/Perspective.cpp
SOURCES += src/front/HTTPServer.cpp
SOURCES += src/event/QueryEvent.cpp
SOURCES += src/event/WordEvent.cpp
SOURCES += src/event/FeatureEvent.cpp
SOURCES += src/event/LocationEvent.cpp
SOURCES += src/event/SignatureEvent.cpp
SOURCES += src/event/DetectionEvent.cpp
SOURCES += src/event/FailureEvent.cpp
SOURCES += src/stage/VisibilityStage.cpp
SOURCES += src/stage/WordSearchStage.cpp
SOURCES += src/stage/SignatureSearchStage.cpp
SOURCES += src/stage/PerspectiveStage.cpp
SOURCES += src/stage/FeatureStage.cpp
SOURCES += src/main.cpp

TARGET = cellmate
