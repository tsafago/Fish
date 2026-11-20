#include <math.h>

static double coast_line(double normalized_y) {
    static const double knots[]={201,229,237,254,258,246,231,234,253,283,295,298,320,336,339,353,385};
    double v=fmin(1,fmax(0,normalized_y))*16;
    int i=min(15,(int)v); double t=v-i,p=knots[max(0,i-1)],a=knots[i],b=knots[i+1],q=knots[min(16,i+2)];
    return a+.5*t*((b-p)+t*((2*p-5*a+4*b-q)+t*(3*a-3*b+q-p)));
}
