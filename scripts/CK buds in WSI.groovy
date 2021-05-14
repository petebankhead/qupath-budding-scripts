/**
 * QuPath v0.2 script to identify tumor buds in larger tissue sections.
 *
 * This depends upon having a pre-trained pixel classifier for tumour detection 
 * (based upon CK staining).
 * This should be saved within the current QuPath project, and the name inserted 
 * into the script.
 *
 * Code by Pete, with ideas and applications by Natalie and Maurice.
 * @author Pete Bankhead
 * @author Natalie Fischer
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

// TODO: Define the name of the pixel classifier used to tumor detection (i.e. positive CK staining)
def tumorClassifier = 'High smoothing 1 threshold 0.4'

// TODO: Define min & max areas used to identify tumor buds
// These are given in calibrated units (usually µm, assuming this information is available in the image)
double minBudArea = 40.0
double maxBudArea = 700.0

// TODO: Specify if 'holes' should be filled in tumor areas
// This can be useful to exclude false positives within necrotic areas
boolean fillTumorHoles = true

// Set the image type; here, default stain vectors as used
setImageType('BRIGHTFIELD_H_DAB')
setColorDeconvolutionStains('{"Name" : "H-DAB default", "Stain 1" : "Hematoxylin", "Values 1" : "0.65111 0.70119 0.29049 ", "Stain 2" : "DAB", "Values 2" : "0.26917 0.56824 0.77759 ", "Background" : " 255 255 255 "}')

// Check if we already have detections
if (getDetectionObjects()) {
    println 'There are already detections in the current image! These will be removed...'
    clearDetections()
}

// Expand manually annotated invasive front 
def lineAnnotations = getAnnotationObjects().findAll {it.isAnnotation() && it.getROI().isLine()}
double maxHoleArea = fillTumorHoles ? Double.POSITIVE_INFINITY : 0
if (lineAnnotations) {
    selectObjects(lineAnnotations)
    runPlugin('qupath.lib.plugins.objects.DilateAnnotationPlugin', '{"radiusMicrons": 1000.0,  "lineCap": "Round",  "removeInterior": false,  "constrainToParent": true}');
}

// Retain the tissue annotations for later
def tissueAnnotations = new ArrayList<>(getAnnotationObjects())

// Create detections for tumor regions
selectAnnotations()
createDetectionsFromPixelClassifier(tumorClassifier, minBudArea, maxHoleArea, "SPLIT", "DELETE_EXISTING")

// Identify buds by eliminating all detections that are out-of-range or have the wrong classification
def cal = getCurrentServer().getPixelCalibration()
if (!cal.hasPixelSizeMicrons())
    println 'Warning! Pixel calibration info is not available in µm - check the bud size thresholds are reasonable'
double pixelWidth = cal.getPixelWidth()
double pixelHeight = cal.getPixelHeight()
def toRemove = getDetectionObjects().findAll {it.getPathClass() != getPathClass('Tumor') || it.getROI().getScaledArea(pixelWidth, pixelHeight) > maxBudArea}
removeObjects(toRemove, true)

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

// Add shape measurements to the buds
selectDetections()
addShapeMeasurements("AREA", "LENGTH", "CIRCULARITY", "SOLIDITY")

// Optionally, other criteria to remove buds could be included here.
// For example, to delete buds with low circularity you could use the following:
//
//def nonCircularBuds = getDetectionObjects().findAll {
//    it.isDetection() && 
//    it.getPathClass() == getPathClass('Tumor bud') &&
//    measurement(it, 'Circularity') < 0.5}
//removeObjects(nonCircularBuds, true)