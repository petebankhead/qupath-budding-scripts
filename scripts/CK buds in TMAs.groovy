/**
 * QuPath v0.2 script to identify tumor buds in TMA images.
 *
 * This depends upon pre-trained pixel classifiers for:
 *  - tissue detection
 *  - tumour detection (based upon CK staining)
 * These should be saved within the current QuPath project, and the names need to be
 * inserted into the script.
 *
 * Code by Pete, with ideas and applications by Natalie and Maurice.
 * @author Pete Bankhead
 * @author Natalie Fisher
 * @author Maurice Loughrey
 */


import org.locationtech.jts.algorithm.locate.SimplePointInAreaLocator
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Location
import qupath.lib.objects.PathObject
import qupath.lib.objects.PathObjects
import qupath.lib.roi.GeometryTools

import java.util.stream.Collectors

import static qupath.lib.gui.scripting.QPEx.*

// TODO: Define the name of the pixel classifier used for tissue detection
def tissueClassifier = 'Tissue detection'

// TODO: Define the name of the pixel classifier used to tumor detection (i.e. positive CK staining)
def tumorClassifier = 'High smoothing 1 threshold 0.4'

// TODO: Define min & max areas used to identify tumor buds
// These are given in calibrated units (usually µm, assuming this information is available in the image)
double minBudArea = 40.0
double maxBudArea = 700.0

// TODO: Specify if 'holes' should be filled in tumor areas
// This can be useful to exclude false positives within necrotic areas
boolean fillTumorHoles = true

// TODO: Define distance to erode tissue annotations; this avoids detections at the tissue border
double tissueErodeDistance = 30.0

// Set the image type; here, default stain vectors as used
setImageType('BRIGHTFIELD_H_DAB')
setColorDeconvolutionStains('{"Name" : "H-DAB default", "Stain 1" : "Hematoxylin", "Values 1" : "0.65111 0.70119 0.29049 ", "Stain 2" : "DAB", "Values 2" : "0.26917 0.56824 0.77759 ", "Background" : " 255 255 255 "}')

// Ensure the image has been dearrayed
// If not, stop after dearraying to enable manual curation
if (!isTMADearrayed()) {
    runPlugin('qupath.imagej.detect.dearray.TMADearrayerPluginIJ', '{"coreDiameterMM": 1.2,  "labelsHorizontal": "1-16",  "labelsVertical": "A-Q",  "labelOrder": "Row first",  "densityThreshold": 5,  "boundsScale": 105}')
    println 'Please check the dearraryed result and correct any cores before running the script again'
    return
}

// Check if we already have annotations or detections
if (getAnnotationObjects()) {
    println 'There are already annotations in the current image! These will be removed...'
    clearAnnotations()
}
if (getDetectionObjects()) {
    println 'There are already detections in the current image! These will be removed...'
    clearDetections()
}

// Create tissue annotations
selectTMACores()
createAnnotationsFromPixelClassifier(tissueClassifier, 0.0, 0.0, "INCLUDE_IGNORED")

// Exclude any tissue annotations classified as Ignore*
selectObjectsByClassification("Ignore*")
clearSelectedObjects(true)

// Retain the tissue annotations for later
def tissueAnnotations = new ArrayList<>(getAnnotationObjects())

// Create detections for tumor regions
selectAnnotations()
double maxHoleArea = fillTumorHoles ? Double.POSITIVE_INFINITY : 0
createDetectionsFromPixelClassifier(tumorClassifier, minBudArea, maxHoleArea, "SPLIT", "DELETE_EXISTING")

// Identify buds by eliminating all detections that are out-of-range or have the wrong classification
def cal = getCurrentServer().getPixelCalibration()
if (!cal.hasPixelSizeMicrons())
    println 'Warning! Pixel calibration info is not available in µm - check the bud size thresholds are reasonable'
double pixelWidth = cal.getPixelWidth()
double pixelHeight = cal.getPixelHeight()
def toRemove = getDetectionObjects().findAll {it.getPathClass() != getPathClass('Tumor') || it.getROI().getScaledArea(pixelWidth, pixelHeight) > maxBudArea}
removeObjects(toRemove, true)

// Update the tissue annotations
if (tissueErodeDistance != 0) {
    double erodeDistance = tissueErodeDistance / (pixelWidth/2.0 + pixelHeight/2.0)
    Map<PathObject, PathObject> tissueUpdated = tissueAnnotations.parallelStream().collect(Collectors.toMap(a -> a, a -> refineTissueAnnotation(a, erodeDistance)))
    for (entry in tissueUpdated.entrySet()) {
        if (entry.getKey() != entry.getValue()) {
            def parent = entry.getKey().getParent()
            parent.removePathObject(entry.getKey())
            parent.addPathObject(entry.getValue())
        }
    }
}
fireHierarchyUpdate()

// Reclassify tumor detections as buds
getDetectionObjects().each {
    if (it.getPathClass() == getPathClass('Tumor'))
        it.setPathClass(getPathClass('Tumor bud', getColorRGB(150, 20, 20)))
    else
        // This shouldn't happen...
        println "Warning! Somehow a detection has been created that isn't 'Tumor'"
}

// Add area measurements to annotations (the tumor bud counts should be generated automatically)
selectAnnotations()
addPixelClassifierMeasurements(tumorClassifier, "Classified")


/**
 * Shrink tissue annotations to remove parts at the TMA core boundary -
 * these can be less reliable (with darker staining, other fragments).
 *
 * Do this after filling holes to avoid shrinking inside the tissue,
 * and remove possible buds with centroids outside the updated tissue.
 *
 * @param pathObject the input annotation to refine
 * @param erodeDistance the distance (in pixels) to erode the ROI;
 *                      the absolute value is used, so the input may be positive or negative
 * @return an updated annotation, or the original object if erodeDistance == 0 or an exception occurred during the
 *         refinement operations
 */
PathObject refineTissueAnnotation(PathObject pathObject, double erodeDistance) {
    if (erodeDistance == 0)
        return pathObject
    try {
        erodeDistance = Math.abs(erodeDistance)
        def roi = pathObject.getROI()
        def geom = roi.getGeometry()
        def geom2 = GeometryTools.fillHoles(geom)
        geom2 = geom2.buffer(-erodeDistance)
        geom2 = geom2.intersection(geom)
        def roi2 = GeometryTools.geometryToROI(geom2, roi.getImagePlane())
        def pathObject2 = PathObjects.createAnnotationObject(roi2, pathObject.getPathClass())
        // Transfer detections with centroids that fall within the new object
        def childDetections = pathObject.getChildObjects().findAll {it.isDetection()}
        if (childDetections) {
            def locator = new SimplePointInAreaLocator(geom2)
            for (detection in childDetections) {
                def centroid = new Coordinate(detection.getROI().getCentroidX(), detection.getROI().getCentroidY())
                if (locator.locate(centroid) != Location.EXTERIOR)
                    pathObject2.addPathObject(detection)
            }
        }
        return pathObject2
    } catch (Exception e) {
        println "Failed to refine tissue annotation for ${pathObject}: ${e.getLocalizedMessage()}"
        return pathObject
    }
}
